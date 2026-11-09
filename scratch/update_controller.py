import sys

path = 'app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderTranslationController.kt'
with open(path, 'r', encoding='utf-8') as f:
    c = f.read()

import_target = 'import eu.kanade.translation.model.Translation'
new_imports = '''import eu.kanade.translation.PageColorizeOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation'''
assert import_target in c, 'import_target not found'
c = c.replace(import_target, new_imports, 1)

prop_target = '    private val pageArrivalTimes get() = owner.pageArrivalTimes'
new_prop = '''    private val pageArrivalTimes get() = owner.pageArrivalTimes
    private val autoColorizeInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()'''
assert prop_target in c, 'prop_target not found'
c = c.replace(prop_target, new_prop, 1)

reset_target = '''    internal fun resetAutoTranslationState() {
        // Invalidate loader callbacks and resolver-held page/stream handles before any caller
        // cancels a queue session or unreferences the chapter's loader resources.
        clearReaderSourceReadiness()
        autoPageResolver.invalidate()
    }'''
new_reset = '''    internal fun resetAutoTranslationState() {
        // Invalidate loader callbacks and resolver-held page/stream handles before any caller
        // cancels a queue session or unreferences the chapter's loader resources.
        autoColorizeInFlight.clear()
        clearReaderSourceReadiness()
        autoPageResolver.invalidate()
    }'''
assert reset_target in c, 'reset_target not found'
c = c.replace(reset_target, new_reset, 1)

methods = '''
    internal fun handleAutoColorize(currentPage: ReaderPage) {
        if (!translationPreferences.translationColorizationEnabled().get() ||
            !translationPreferences.autoColorize().get()
        ) {
            return
        }
        val pages = currentPage.chapter.pages ?: return
        val currentIndex = pages.indexOfFirst { it === currentPage }
        if (currentIndex < 0) return

        val targets = listOfNotNull(
            pages.getOrNull(currentIndex),
            pages.getOrNull(currentIndex + 1),
        ).filterIsInstance<ReaderPage>()

        val manga = manga ?: return
        val source = sourceManager.get(manga.source) as? HttpSource ?: return

        for (targetPage in targets) {
            val pageKey = resolvePageKey(targetPage)
            if (!autoColorizeInFlight.add(pageKey)) {
                continue
            }

            viewModelScope.launchIO {
                try {
                    val store = currentTranslationStore
                    if (store != null) {
                        val translation = targetPage.translation
                        if (translation == null || translation.blocks.isEmpty()) {
                            val hydrated = store.getOrLoadPageSnapshot(pageKey)
                            if (hydrated != null) {
                                targetPage.translation = hydrated
                            }
                        }
                    }

                    if (targetPage.translation?.coloredImageName != null &&
                        targetPage.translation?.coloringStatus == StageStatus.READY
                    ) {
                        // Reuse valid saved colors without text detection or color re-execution
                        rebindTranslatedStreamForPref(targetPage)
                        targetPage.showTranslatedImage = true
                        eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(setOf(targetPage)))
                        mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }
                        return@launchIO
                    }

                    val streamFn = actions.resolveColorizePageStreamFn(targetPage, source)
                    if (streamFn == null) {
                        logcat(LogPriority.WARN) { "Auto-colorize stream resolution failed for $pageKey" }
                        return@launchIO
                    }
                    val domainChapter = targetPage.chapter.chapter.toDomainChapter() ?: return@launchIO
                    val outcome = translationManager.colorizeCurrentPage(
                        manga = manga,
                        chapter = domainChapter,
                        source = source,
                        pageKey = pageKey,
                        streamFn = streamFn,
                    )
                    if (outcome == PageColorizeOutcome.COMPLETED) {
                        rebindTranslatedStreamForPref(targetPage)
                        targetPage.showTranslatedImage = true
                        eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(setOf(targetPage)))
                        mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.WARN, e) { "Auto-colorize error on page $pageKey" }
                } finally {
                    autoColorizeInFlight.remove(pageKey)
                }
            }
        }
    }

    internal fun colorizeCurrentPageForAuto() {
        val page = owner.currentPageReaderPage() ?: return
        handleAutoColorize(page)
    }

    internal fun setAutoColorize(auto: Boolean) {
        translationPreferences.autoColorize().set(auto)
        if (auto && translationPreferences.translationColorizationEnabled().get()) {
            colorizeCurrentPageForAuto()
        }
    }
'''

set_auto_target = '''    internal fun setAutoTranslate(auto: Boolean) {
        translationPreferences.autoTranslate().set(auto)
    }'''

assert set_auto_target in c, 'set_auto_target not found'
c = c.replace(set_auto_target, set_auto_target + '\n' + methods, 1)

with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(c)
print('SUCCESS')
