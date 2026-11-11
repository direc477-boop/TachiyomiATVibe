package eu.kanade.translation.persistence

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** Result of a chapter artifact deletion attempt. */
data class ChapterArtifactDeletionResult(
    val manifestRemoved: Boolean,
    val artifactTreeRemoved: Boolean,
    val failures: List<String>,
    /**
     * P3B: true when the destructive empty-manifest checkpoint could not be
     * published with a VERIFIED same-state backup before the authority
     * removal. Typed incomplete, never a rollback: the file removal still
     * happened (or failed for its own recorded reasons), but a crash mid-
     * deletion could restore the pre-deletion generation from the retained
     * backup until a retry completes the removal.
     */
    val destructiveCheckpointUnverified: Boolean = false,
    /** Files actually removed across manifest siblings and the artifact tree. */
    val removedFiles: Int = 0,
    /** Directories actually removed inside the artifact tree, including its root. */
    val removedDirs: Int = 0,
) {
    val complete: Boolean get() = failures.isEmpty()
}

/**
 * Captures the exact artifact-owned paths before chapter teardown.
 * The plan is deliberately read-only until [delete] is called after all workers have
 * been cancelled and joined.
 */
class ChapterArtifactDeletionPlan private constructor(
    private val io: ChapterDocumentIo,
    val layout: ChapterArtifactLayout,
    private val manifestSiblingNames: List<String>,
) {
    /**
     * Removes the authority document before the owned tree. If any authority copy
     * cannot be removed, the tree is retained to avoid partial data loss and the
     * caller receives a retryable failure. Once all authority copies are gone,
     * leftover payloads are orphaned rather than readable if tree deletion fails.
     *
     * P3B: the removal is preceded by ONE checked destructive publication of
     * an EMPTY current-format manifest through
     * [ChapterArtifactEngine.publishDestructiveManifest] — the same
     * verified-same-state-backup requirement every destructive operation
     * satisfies. A crash between that checkpoint and the file removals can
     * then only restore an EMPTY authority, never the deleted chapter's
     * generation state; re-running the idempotent plan finishes the removal.
     * A checkpoint refusal (future-schema primary/backup preserved, or the
     * publication itself failing) still attempts the plain removal and
     * reports the typed incompleteness.
     */
    fun delete(): ChapterArtifactDeletionResult {
        val failures = mutableListOf<String>()
        var checkpointUnverified = false
        var removedFiles = 0
        var removedDirs = 0
        val documents = AtomicChapterDocuments(io)
        val engine = ChapterArtifactEngine(documents, layout)
        when (
            val receipt = engine.publishDestructiveManifest(
                ChapterArtifactManifest(chapterKey = layout.chapterKey),
            )
        ) {
            is ChapterArtifactEngine.DestructiveManifestReceipt.Complete -> Unit
            is ChapterArtifactEngine.DestructiveManifestReceipt.PrimaryAdvancedBackupIncomplete -> {
                checkpointUnverified = true
                logcat(LogPriority.WARN) {
                    "TachiyomiAT chapter artifact deletion checkpoint unverified: " +
                        "chapter=${layout.chapterKey} reason=${receipt.reason}"
                }
            }
            is ChapterArtifactEngine.DestructiveManifestReceipt.NotPublished -> logcat(LogPriority.WARN) {
                "TachiyomiAT chapter artifact deletion checkpoint not published: " +
                    "chapter=${layout.chapterKey} reason=${receipt.reason}"
            }
        }
        var manifestRemoved = true

        manifestSiblingNames.forEach { name ->
            when (deleteCountingIfPresent(name, failures)) {
                DeleteOutcome.ABSENT -> Unit
                DeleteOutcome.REMOVED -> removedFiles++
                DeleteOutcome.FAILED -> manifestRemoved = false
            }
        }
        if (!manifestRemoved) {
            logcat(LogPriority.ERROR) {
                "TachiyomiAT chapter artifact deletion retained owned tree: " +
                    "manifest removal failed chapter=${layout.chapterKey}"
            }
            return ChapterArtifactDeletionResult(
                manifestRemoved = false,
                artifactTreeRemoved = false,
                failures = failures,
                destructiveCheckpointUnverified = checkpointUnverified,
            )
        }

        // Count the tree before the single destructive root removal: UniFile/SAF
        // delete() drops a directory subtree wholesale and reports no contents.
        val (treeFiles, treeDirs) = countTreeEntries(layout.artifactRootDirectoryName)
        val artifactTreeRemoved = deleteCountingIfPresent(layout.artifactRootDirectoryName, failures).let {
            if (it == DeleteOutcome.REMOVED) {
                removedFiles += treeFiles
                removedDirs += treeDirs + 1
                true
            } else {
                it == DeleteOutcome.ABSENT
            }
        }
        return ChapterArtifactDeletionResult(
            manifestRemoved = true,
            artifactTreeRemoved = artifactTreeRemoved,
            failures = failures,
            destructiveCheckpointUnverified = checkpointUnverified,
            removedFiles = removedFiles,
            removedDirs = removedDirs,
        )
    }

    private enum class DeleteOutcome { ABSENT, REMOVED, FAILED }

    private fun deleteCountingIfPresent(name: String, failures: MutableList<String>): DeleteOutcome {
        if (!io.exists(name)) return DeleteOutcome.ABSENT
        if (io.delete(name) || !io.exists(name)) return DeleteOutcome.REMOVED
        failures += name
        return DeleteOutcome.FAILED
    }

    /** Counts files and nested directories under [directoryName] via the io seam. */
    private fun countTreeEntries(directoryName: String): Pair<Int, Int> {
        var files = 0
        var dirs = 0
        for (child in io.list(directoryName).orEmpty()) {
            val childName = "$directoryName/$child"
            val childList = io.list(childName)
            if (childList != null) {
                val (childFiles, childDirs) = countTreeEntries(childName)
                files += childFiles
                dirs += childDirs + 1
            } else {
                files++
            }
        }
        return files to dirs
    }

    companion object {
        /** Captures a deletion plan without opening a translation store. */
        fun capture(io: ChapterDocumentIo, artifactFileName: String): ChapterArtifactDeletionPlan? {
            val layout = ChapterArtifactLayout.fromArtifactFileName(artifactFileName)
            val documents = AtomicChapterDocuments(io)
            val manifest = runCatching {
                documents.readValidated<ChapterArtifactManifest>(layout.manifestFileName)
            }.getOrNull()
            // Never destroy a document owned by a newer schema version.
            if (manifest?.schemaVersion?.let { it > ChapterArtifactManifest.SCHEMA_VERSION } == true) {
                return null
            }
            val siblings = ownedManifestSiblingNames(io, layout.manifestFileName)
            if (manifest == null &&
                !io.exists(layout.artifactRootDirectoryName) &&
                siblings.none { io.exists(it) }
            ) {
                return null
            }
            return ChapterArtifactDeletionPlan(
                io = io,
                layout = layout,
                manifestSiblingNames = siblings,
            )
        }

        private fun ownedManifestSiblingNames(io: ChapterDocumentIo, manifestName: String): List<String> {
            val corruptBase = AtomicChapterDocuments.corruptNameFor(manifestName)
            return buildList {
                add(manifestName)
                add(AtomicChapterDocuments.tempNameFor(manifestName))
                add(AtomicChapterDocuments.backupNameFor(manifestName))
                add(corruptBase)
                io.list("")
                    .orEmpty()
                    .filter { it.startsWith("$corruptBase.") }
                    .forEach(::add)
            }.distinct()
        }
    }
}

/**
 * P3B typed reset receipt. A reset is COMPLETE only when every destructive
 * publication behind it durably committed with verified same-state backups.
 * Any unverified backup, rejected publication, or failed authority removal
 * surfaces as typed incomplete — the caller must not present the chapter as
 * cleanly reset (and must not adopt orphan bytes) when [failures] is
 * non-empty.
 */
data class ChapterResetReceipt(
    val failures: List<String>,
    val removedFiles: Int = 0,
    val removedDirs: Int = 0,
) {
    val completed: Boolean get() = failures.isEmpty()

    companion object {
        val COMPLETE = ChapterResetReceipt(failures = emptyList())

        fun incomplete(
            failures: List<String>,
            removedFiles: Int = 0,
            removedDirs: Int = 0,
        ): ChapterResetReceipt = ChapterResetReceipt(
            failures = failures.filter { it.isNotBlank() }.distinct(),
            removedFiles = removedFiles,
            removedDirs = removedDirs,
        )
    }
}
