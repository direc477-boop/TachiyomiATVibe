package eu.kanade.translation.model

data class PageView(
    val imageName: String?,
    val lifecycle: PageLifecycle,
    val overlay: OverlayState,
    val errorMessage: String?,
    val translationReady: Boolean,
    val overlayFingerprint: String?,
    val coloredImageName: String? = null,
) {
    enum class OverlayState {
        Idle,
        Running,
        Error,
    }
}

fun PageTranslationView?.toPageView(colorizationEnabled: Boolean = false): PageView {
    if (this == null) {
        return PageView(
            imageName = null,
            lifecycle = PageLifecycle.Pending,
            overlay = PageView.OverlayState.Idle,
            errorMessage = null,
            translationReady = false,
            overlayFingerprint = null,
            coloredImageName = null,
        )
    }
    val imageName = displayImageName(colorizationEnabled)
    val overlay = when {
        isStageRunning -> PageView.OverlayState.Running
        isStageFailed && imageName == null -> PageView.OverlayState.Error
        else -> PageView.OverlayState.Idle
    }
    return PageView(
        imageName = imageName,
        lifecycle = lifecycle,
        overlay = overlay,
        errorMessage = errorMessage,
        translationReady = isTranslationDisplayReady,
        overlayFingerprint = overlayContentFingerprint.takeIf { isTranslationDisplayReady },
        coloredImageName = coloredImageName.takeIf { coloringStatus == StageStatus.READY },
    )
}

val PageTranslationView.overlayContentFingerprint: String
    get() = blocks.joinToString(separator = ":") { it.stableFingerprint() }
