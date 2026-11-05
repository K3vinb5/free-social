package dev.k3vinb5.freesocial.filter

/** A screen swipe in pixels: start point → end point, used for a straight-line gesture. */
data class SwipePath(val startX: Int, val startY: Int, val endX: Int, val endY: Int)

/**
 * Geometry for [dev.k3vinb5.freesocial.core.Action.SCROLL_PAST]: swipe the matched cell/shelf up
 * and out of view without leaving the app.
 *
 * The swipe is vertical and bottom→top, spanning [SPAN_FRACTION] of the node's own height starting
 * [BOTTOM_INSET_FRACTION] above its bottom edge, so a shelf or a full-height reel cell is pushed
 * past the viewport instead of being dragged from the very edge (which system gesture areas eat).
 * Pure so the geometry is unit-testable without Android.
 */
object ScrollPastGesture {
    const val SPAN_FRACTION = 0.6f
    const val BOTTOM_INSET_FRACTION = 0.1f

    /** Below this a swipe cannot be expressed reliably; the caller skips the gesture. */
    const val MIN_HEIGHT_PX = 32

    fun swipePast(bounds: Bounds): SwipePath? {
        if (bounds.isEmpty || bounds.height < MIN_HEIGHT_PX) return null
        val height = bounds.height
        val startY = bounds.bottom - (height * BOTTOM_INSET_FRACTION).toInt()
        val endY = startY - (height * SPAN_FRACTION).toInt()
        if (endY < bounds.top) return null
        return SwipePath(bounds.centerX, startY, bounds.centerX, endY)
    }
}
