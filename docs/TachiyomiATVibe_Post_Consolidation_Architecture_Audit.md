# TachiyomiATVibe — Post-Consolidation Architecture Audit

**Status:** Preliminary, read-only findings — **not** an implementation plan  
**Reviewed revision:** `167cdc64a8473db7a13ff0bf52fc07f8ef7b21ea` (`main`, inspected October 20, 2026)  
**Source root:** `app/src/main/java/eu/kanade/translation/`  
**Standard:** Project `AGENTS.md` — deep modules, high locality, clear seams, no unnecessary wrappers, verify before pruning.  
**Additional project target:** An ordinary feature change should require understanding **five or fewer files**. This is a local maintainability heuristic, **not** an explicit rule in `AGENTS.md`.

## 1. Executive verdict

**The package consolidation is directionally good; responsibility boundaries are not yet consistently enforced.** The old `scheduling/`, `workflow/` and `pipeline/` directories are gone. The current design separates `coordinator/` (admission/lifecycle), `processing/` (execution) and `persistence/` (state/artifacts). Keep these boundaries for now; do not merge everything into a new god module.

**Top concern:** The active Batch executor is much simpler than its retained artifact-aware planning and durable-state machinery, but its writes and completion checks do not clearly preserve all of those mechanisms' guarantees. Fix the small number of ownership seams before further aggressive pruning.

**Inventory at reviewed commit:** `processing/`: **26** Kotlin files; `persistence/`: **31** Kotlin files. Scope includes an initial look at `context/` (**2** Kotlin files) because it affects Batch functionality. This is a targeted source inspection and reference search, **not** a verified caller-by-caller audit of all 59 files or a run of the Android test suite.

## 2. Ownership map

| Boundary | Intended owner | Assessment |
|---|---|---|
| Public app entry | `TranslationManager` | Reasonable facade; avoid adding decisions here. |
| Manual/Auto/Batch admission, queue, cancellation | `coordinator/` | Appropriate boundary; several coordinators remain very large. |
| OCR/translation/inpainting execution | `processing/TranslationProcessor` plus `BatchPageOperation` | Shared stages are sensible; Batch has separate sequencing and unsafe-looking write integration. |
| Native execution/memory safety | `processing/execution/`, `EngineLane`, `processing/memory/`, `adaptive/` | Keep necessary resource owners; test actual native-exit and release rules. |
| Live page state, lease and guarded mutation | `persistence/ChapterTranslationStore` plus focused helpers | Ownership is comparatively clear. |
| Durable candidate/committed artifacts | `persistence/ChapterArtifactEngine` plus helpers | Necessary deep boundary; do not collapse transaction semantics merely to reduce file count. |
| Chapter reset lifecycle | `persistence/ChapterDataResetController` | **Misplaced orchestration:** directly references Batch coordination and drives cancellation/teardown. |
| Previous-translation context | `context/ChapterContextService`, `BatchContextFrontier` | No discovered production callers; may be orphaned from current execution. |

## 3. Processing: directory assessment

| Directory | Finding | Provisional decision |
|---|---|---|
| `adaptive/` | Device priority/permit gate has a distinct responsibility. | **KEEP** |
| `execution/` | Native-call quarantine and stream ownership are important, but check whether every helper has a production caller. | **KEEP / AUDIT** |
| `memory/` | Device memory budgeting and reclamation have a coherent role. | **KEEP** |
| `batch/` and `batch/progress/` | Batch types/progress coexist with the executor in the processing root; some progress semantics are historical. | **SIMPLIFY** |
| `planning/` | Rich work planner appears disconnected from active Batch processing. | **VERIFY, then MERGE or DELETE obsolete rules** |
| Processing root | A collection of executor, writes, publication and resource helpers. Some may be test-only or leftover. | **AUDIT production references before pruning** |

### P-01 — Competing Batch work-planning rules (**high; confirmed structural mismatch**)

- [`PageWorkPlanner.kt`](https://github.com/KimreneOuk/TachiyomiATVibe/blob/167cdc64a8473db7a13ff0bf52fc07f8ef7b21ea/app/src/main/java/eu/kanade/translation/processing/planning/PageWorkPlanner.kt) checks stage dependencies, fingerprints, payload validity, and retry evidence.
- The active [`BatchPageOperation.kt`](https://github.com/KimreneOuk/TachiyomiATVibe/blob/167cdc64a8473db7a13ff0bf52fc07f8ef7b21ea/app/src/main/java/eu/kanade/translation/processing/BatchPageOperation.kt#L89-L105) instead classifies a page as completed when translation is `READY` or it is textless.
- A repository reference search did **not** locate production calls to `PageWorkPlanner.plan`; its planner tests do not establish that production uses it.
- [`BatchPageOperationTest.kt`](https://github.com/KimreneOuk/TachiyomiATVibe/blob/167cdc64a8473db7a13ff0bf52fc07f8ef7b21ea/app/src/test/java/eu/kanade/translation/processing/BatchPageOperationTest.kt) explicitly expects translation-ready pages with failed inpainting to be skipped.

**Simplification action:** Decide what “reusable/completed page” actually means. Put the rule at **one** authoritative seam; delete the unused alternative only after validating required provenance/retry behavior. Avoid adding a third planner.

### P-02 — Batch result writes are not fenced to the original attempt (**high; correctness risk**)

- The Reader path captures a lease and an original write precondition before processing, then calls `updatePageGuarded` ([`ReaderTranslationCoordinator.kt`, lines 432–483](https://github.com/KimreneOuk/TachiyomiATVibe/blob/167cdc64a8473db7a13ff0bf52fc07f8ef7b21ea/app/src/main/java/eu/kanade/translation/coordinator/ReaderTranslationCoordinator.kt#L432-L483)).
- Batch repeatedly calls `store.updatePageFromCurrentSnapshot(...)` **after** OCR, translation or inpainting work; this API takes a fresh snapshot just before writing ([`BatchPageOperation.kt`, lines 119–215](https://github.com/KimreneOuk/TachiyomiATVibe/blob/167cdc64a8473db7a13ff0bf52fc07f8ef7b21ea/app/src/main/java/eu/kanade/translation/processing/BatchPageOperation.kt#L119-L215)).
- Several Batch calls also ignore `ChapterTranslationStore.PatchResult`, so a rejected commit need not stop reported progress.

**Simplification action:** Use **one** page-write rule for both execution modes: capture ownership before asynchronous work; accept/reject the eventual result against that identity; check the returned receipt. Reuse existing store fences rather than inventing new safety layers. This is an identified race/false-success **risk**, not proof that corruption has occurred.

### P-03 — Batch progress can assert completion without proving durability (**high; contract mismatch**)

[`BatchProgressReconciler.reconcileFlaggedCompleted()`](https://github.com/KimreneOuk/TachiyomiATVibe/blob/167cdc64a8473db7a13ff0bf52fc07f8ef7b21ea/app/src/main/java/eu/kanade/translation/processing/batch/progress/BatchProgressReconciler.kt#L96-L155) explicitly treats even a missing expected page as done for a supposedly finalized Batch. That assumption is only safe if the caller has already verified every page's terminal state and committed output. The current executor's ignored write results make this precondition unproven.

**Simplification action:** Make “Batch finished” depend on verified final store/artifact results, not only loops ending. Prefer deleting the special completion assumption if one ordinary projection can express correct terminal state.

### P-04 — Actual Batch concurrency has a narrower dependency than requested (**behavior-dependent**)

[`BatchPageOperation.kt`](https://github.com/KimreneOuk/TachiyomiATVibe/blob/167cdc64a8473db7a13ff0bf52fc07f8ef7b21ea/app/src/main/java/eu/kanade/translation/processing/BatchPageOperation.kt#L166-L225) does a full OCR pass, then chunks translation and feeds a bounded sequential inpainting worker **after each translated page**. Inpainting can overlap later translation chunks, but does not start immediately after OCR. Do not reorder it until its data dependencies and model-residency constraints are confirmed.

### P-05 — Historical execution and rendering seams (**candidate cleanup, not approved deletions**)

- `PageWorkPlanner` retains `LAYOUT` and `runRender` decisions.
- `TranslationBatchProgressTracker` retains `RENDER` events and envelope-plan progress APIs.
- `persistence/TranslationStageContracts.kt` and `ChapterStageMerges.kt` retain render patches/merges.
- Search found no external production calls to `mergeRender`, `markRenderRunning`, or `DeferredPagePublications` within the inspected source results. **A zero-result search is not a whole-project deletion proof.**

**Simplification action:** Verify all code, reflection, persistence migration and test references before retiring old producer-rendering contracts. Keep historical read compatibility if existing user artifacts require it.

## 4. Persistence: ownership assessment

**What to preserve:** `ChapterTranslationStore` + page lease table + guarded page mutations; `ChapterArtifactEngine` + candidate transactions + committed pointers + recovery/retention. These maintain last-good results, stale-write rejection and crash safety. Their individual boundaries are purposeful even if some files are large.

### S-01 — Reset coordination is in the wrong layer (**MOVE/SIMPLIFY**)

[`ChapterDataResetController.kt`](https://github.com/KimreneOuk/TachiyomiATVibe/blob/167cdc64a8473db7a13ff0bf52fc07f8ef7b21ea/app/src/main/java/eu/kanade/translation/persistence/ChapterDataResetController.kt) imports `BatchTranslationCoordinator` and coordinates cancel/join, queue removal, stream teardown and artifact deletion. This causes persistence to reach *upward* into orchestration.

**Smallest fix:** Move the **reset sequence ownership** to an existing coordinator or a focused coordinator-side reset entry, retaining persistence-only deletion/reset functions below it. Do not replace the controller with a forest of forwarding interfaces. Preserve cancellation-before-deletion ordering.

### S-02 — Persistence root is flat, but package moves alone have low value (**KEEP structure temporarily**)

The 31 files represent distinct jobs: live store, leases, guarded mutations, durable file I/O, candidate publication, recovery, retention, reset and status. Do not reorganize them into additional folders until the actual cross-file ownership problems are removed. Avoid a cosmetic migration that changes imports without reducing change scope.

### S-03 — Serialized render/layout data must be separated from active behavior (**AUDIT / PARTIALLY DELETE**)

`ChapterArtifactManifest` still has a per-page `layout` record and committed layout fingerprint. Some historical serialized fields may be needed to read existing files; this does **not** mean producer render-stage execution must remain. Separate legacy decoding from active stage mutation before removing it.

## 5. `context/`: likely disconnected from the active executor

- [`ChapterContextService.kt`](https://github.com/KimreneOuk/TachiyomiATVibe/blob/167cdc64a8473db7a13ff0bf52fc07f8ef7b21ea/app/src/main/java/eu/kanade/translation/context/ChapterContextService.kt) reconstructs previous translated source→target pairs from durable chapter snapshots.
- [`BatchContextFrontier.kt`](https://github.com/KimreneOuk/TachiyomiATVibe/blob/167cdc64a8473db7a13ff0bf52fc07f8ef7b21ea/app/src/main/java/eu/kanade/translation/context/BatchContextFrontier.kt) prevents context from advancing past missing/failed earlier pages.
- Production reference searches located no callers of `ChapterContextService` and no caller of `BatchContextFrontier` outside that service; their contract tests remain.
- Providers and `engines/translator/contextual/` separately support rolling context at the request/prompt level.

**Provisional decision:** **VERIFY → SIMPLIFY/MERGE/DELETE**, depending on whether previous-chunk context should be Batch-only, available in Manual/Auto, or disabled. Do not delete provider-side context support by association.

## 6. `AGENTS.md` compliance

| Rule | Current observation | Desired next move |
|---|---|---|
| **Deep modules, not shallow wrappers** | Some facade delegation is appropriate, but ownership spans several coordinators; unused helpers may remain. | Remove unnecessary forwarding; preserve genuinely deep safety/storage owners. |
| **High locality** | Batch work touches execution, progress, planning and durable state contracts; a simple change is hard to isolate. | Batch policy should normally change in `processing/batch/` plus a small stable store seam. |
| **Clear seams** | Reader uses captured attempt preconditions; Batch refreshes snapshots on write. | A shared, explicitly guarded write contract. |
| **Reuse essential logic; keep distinct flows separate** | Planner and Batch executor implement competing validity decisions. | One pure validity rule; keep distinct Manual/Auto/Batch admission flows. |
| **No-flag rule** | No evidence that merging reader and Batch orchestration would improve coherence. | Do **not** unify via `isBatch`/`isReader` branching. |
| **200–500 LOC target; 700–800+ review threshold** | `BatchTranslationCoordinator` ~999 LOC; `ReaderTranslationCoordinator` ~812; `TranslationRequestCoordinator` ~915; `ChapterTranslationStore` ~802; `ChapterBulkMutator` ~720. | Review responsibility seams; do not split only to hit a count. |
| **Verify before pruning** | Several no-caller indications are search-only. | Confirm project-wide references and relevant persisted schemas before deletion. |
| **Predictable role naming** | Most store/engine/coordinator names are descriptive; `BatchPageOperation` is Batch-specific yet sits at processing root. | Move for navigation only after behavior is settled. |

## 7. Recommended incremental simplification order

1. **Freeze desired behavior.** Decide whether a Batch page is complete on translation success or only after inpainting; decide whether inpainting can begin immediately after OCR; decide where rolling-context toggle applies.
2. **Establish one page execution/write contract.** Reuse leases, original preconditions and accepted/rejected results; fix ignored Batch write receipts. Add focused stale-write and failed-publication tests.
3. **Unify completion/reuse semantics.** Remove competing planner/completion interpretations; verify missing pages and failed inpainting behavior with targeted tests.
4. **Remove genuinely disconnected leftovers.** Validate all production/test/schema callers for render APIs, envelope progress, unused publication helper and `context/` before deleting.
5. **Resolve reset ownership.** Move cancellation/queue orchestration upward while leaving artifact deletion in persistence.
6. **Only then tidy directories/files.** Aim for ≤5 files an ordinary Batch feature change requires an engineer to understand. Measure this with one real change, not folder counts.

**Do not begin a second massive rearchitecture.** Each step should remove mechanisms or clarify one interface, keep touched files cleaner, and produce a small, reviewable diff.

## 8. Open behavior questions

1. **Completion:** Is translation `READY` enough to mark a Batch page complete when inpainting fails, or must inpainting finish/retry?
2. **Context:** Is previous-chunk translated history intended for Batch only (with an ON/OFF setting), or also Manual and Auto?

A third decision, after these two, is whether inpainting may start as soon as OCR completes or must await translation. No code changes are authorized in this document.

## 9. Verification limits

- Source and selected tests were read at the pinned commit; **no build or test run was performed**.
- Production reference searches are indicators, not proof of dead code. Generated sources, alternate call paths, schema readers and reflection require local verification.
- No measurable performance gain, removable LOC total, or safe deletion set has yet been established.
- The recommendations deliberately prioritize **simplifying ownership**, not adding defensive scaffolding.
