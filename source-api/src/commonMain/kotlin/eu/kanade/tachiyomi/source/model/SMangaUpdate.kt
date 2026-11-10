package eu.kanade.tachiyomi.source.model

data class SMangaUpdate @JvmOverloads constructor(
    val manga: SManga,
    val chapters: List<SChapter>,
    val related: List<SManga>? = null,
)
