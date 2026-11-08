package com.nova.player.engine

/**
 * Bottom padding (as a fraction of view height) for subtitles.
 *
 * Fixed mode: [base] measured from the view bottom.
 * Auto mode (only meaningful when the video is letterboxed with "Fit"):
 *  - thick black bar below the video  -> subtitles are centred inside that bar, so they never cover the picture
 *  - thin / no bar                    -> [base] is measured from the bottom edge of the *video*, not the screen
 */
fun subtitleBottomFraction(
    auto: Boolean, base: Float, fit: Boolean,
    videoW: Int, videoH: Int, viewW: Int, viewH: Int,
): Float {
    if (!auto || !fit || videoW <= 0 || videoH <= 0 || viewW <= 0 || viewH <= 0) return base
    val va = videoW / videoH.toFloat()
    val wa = viewW / viewH.toFloat()
    if (va <= wa) return base                       // pillarboxed or exact fit: no bar under the picture
    val shownH = viewW / va
    val bar = ((viewH - shownH) / 2f / viewH).coerceIn(0f, 0.5f)   // one bar, as a fraction of view height
    val textH = 0.07f
    return if (bar >= textH + 0.02f) ((bar - textH) / 2f).coerceAtLeast(0.02f) else bar + base
}
