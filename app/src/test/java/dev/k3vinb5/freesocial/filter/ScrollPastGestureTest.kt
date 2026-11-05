package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollPastGestureTest {

    @Test
    fun `swipe is vertical, bottom to top, sixty percent of the node height`() {
        val bounds = Bounds(left = 0, top = 100, right = 1080, bottom = 900)
        val swipe = ScrollPastGesture.swipePast(bounds)!!

        assertEquals(bounds.centerX, swipe.startX)
        assertEquals(bounds.centerX, swipe.endX) // vertical
        assertTrue("must move upward", swipe.endY < swipe.startY)
        assertEquals(800 * 0.6f, (swipe.startY - swipe.endY).toFloat(), 1.0f)
        // Starts just above the bottom edge and stays inside the node's rect.
        assertEquals(900 - 80, swipe.startY)
        assertTrue(swipe.endY >= bounds.top)
    }

    @Test
    fun `empty and unusably short rects produce no gesture`() {
        assertNull(ScrollPastGesture.swipePast(Bounds.EMPTY))
        assertNull(ScrollPastGesture.swipePast(Bounds(0, 0, 1080, 20)))
        assertNull(ScrollPastGesture.swipePast(Bounds(0, 0, 0, 900)))
    }

    @Test
    fun `small but usable cells still get a proportional swipe`() {
        val swipe = ScrollPastGesture.swipePast(Bounds(10, 10, 110, 110))!!
        assertEquals(60, swipe.startX)
        assertEquals(110 - 10, swipe.startY)
        assertEquals(60, swipe.startY - swipe.endY)
    }
}
