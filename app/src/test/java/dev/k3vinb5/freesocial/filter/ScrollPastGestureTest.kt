package dev.k3vinb5.freesocial.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1: `SCROLL_PAST` must be framed on the matched node's nearest scrollable ancestor, never on the
 * matched node itself, and must prefer `ACTION_SCROLL_FORWARD` over the synthetic swipe.
 */
class ScrollPastGestureTest {

    private val screen = Bounds(0, 0, 1080, 2424)

    /** 233205-ig_feed_scroll3: a 944 px cell inside a 2020 px RecyclerView named `list`. */
    private fun feedTree(): NodeSnapshot = node(
        index = 0,
        bounds = screen,
        children = listOf(
            node(
                viewId = "$IG:id/list",
                className = "androidx.recyclerview.widget.RecyclerView",
                bounds = Bounds(0, 152, 1080, 2172),
                scrollable = true,
                index = 1,
                children = listOf(
                    node(
                        viewId = "$IG:id/video_container",
                        className = "android.view.ViewGroup",
                        bounds = Bounds(0, 152, 1080, 1096),
                        index = 2,
                    ),
                ),
            ),
        ),
    )

    /** 170110-yt_home: the 58 px "Shorts" shelf header, no scrollable ancestor in the capture. */
    private fun shelfHeaderTree(): NodeSnapshot = node(
        index = 0,
        bounds = screen,
        children = listOf(
            node(
                className = "android.widget.LinearLayout",
                bounds = Bounds(0, 1380, 1080, 2225),
                index = 1,
                children = listOf(
                    node(
                        className = "android.view.ViewGroup",
                        description = "Shorts",
                        bounds = Bounds(169, 1380, 310, 1438),
                        index = 2,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `feed cell frames the swipe on the 2020 px scroll container`() {
        val plan = ScrollPastGesture.plan(feedTree(), targetIndex = 2)

        assertEquals(ScrollPath.ACTION_FORWARD, plan.path)
        assertEquals(Bounds(0, 152, 1080, 2172), plan.frame)
        assertEquals("$IG:id/list", plan.frameNode?.viewId)

        val swipe = plan.swipe!!
        val stroke = swipe.startY - swipe.endY
        assertTrue("stroke $stroke must span >= 2/3 of 2020 px", stroke >= 2020 * 2 / 3 - 1)
        assertTrue("must move upward", swipe.endY < swipe.startY)
        assertTrue("must stay inside the container", swipe.startY <= 2172 && swipe.endY >= 152)
        // Full width of the scrollable, not the matched node's width.
        val covered = swipe.endX - swipe.startX
        assertTrue("x coverage $covered of 1080", covered >= 1080 * 9 / 10)
    }

    @Test
    fun `58 px header never becomes the gesture frame`() {
        val plan = ScrollPastGesture.plan(shelfHeaderTree(), targetIndex = 2)

        assertEquals(ScrollPath.ACTION_FORWARD, plan.path)
        assertNotNull(plan.frame)
        val frame = plan.frame!!
        assertNotEquals(Bounds(169, 1380, 310, 1438), frame)
        assertTrue("frame ${frame.height}px must be usable", frame.height >= ScrollPastGesture.MIN_FRAME_HEIGHT_PX)
        assertTrue("frame must be ancestor sized", frame.height >= 200)
        // The swipe is not 34 px and not centred on the header's 239 px x-position.
        assertTrue(plan.swipe!!.startY - plan.swipe!!.endY >= 200 * 2 / 3)
        assertNotEquals(239, plan.swipe!!.startX)
    }

    @Test
    fun `a scrollable strip shorter than 200 px is rejected for a larger ancestor`() {
        val tree = node(
            index = 0,
            bounds = screen,
            children = listOf(
                node(
                    className = "android.widget.HorizontalScrollView",
                    bounds = Bounds(0, 100, 1080, 158),
                    scrollable = true,
                    index = 1,
                    children = listOf(node(description = "Shorts", bounds = Bounds(169, 100, 310, 158), index = 2)),
                ),
            ),
        )

        val plan = ScrollPastGesture.plan(tree, targetIndex = 2)
        assertEquals(screen, plan.frame)
        assertTrue(plan.frame!!.height >= ScrollPastGesture.MIN_FRAME_HEIGHT_PX)
    }

    @Test
    fun `no ancestor frame means no gesture at all`() {
        val plan = ScrollPastGesture.plan(node(index = 0, bounds = screen), targetIndex = 0)
        assertEquals(ScrollPath.NONE, plan.path)
        assertNull(plan.swipe)
        assertEquals(ScrollOutcome.NO_PLAN, ScrollPastGesture.execute(plan, { true }, { true }))
    }

    @Test
    fun `swipe never frames anything shorter than the minimum`() {
        assertNull(ScrollPastGesture.swipePast(Bounds.EMPTY))
        assertNull(ScrollPastGesture.swipePast(Bounds(0, 0, 1080, 199)))
        assertNull(ScrollPastGesture.swipePast(Bounds(0, 0, 0, 900)))
        val usable = ScrollPastGesture.swipePast(Bounds(0, 0, 1080, 200))!!
        assertTrue(usable.endY < usable.startY)
    }

    @Test
    fun `ACTION_SCROLL_FORWARD is attempted first and the gesture only on false`() {
        val plan = ScrollPastGesture.plan(feedTree(), targetIndex = 2)
        var forwardCalls = 0
        var gestureCalls = 0

        val accepted = ScrollPastGesture.execute(
            plan,
            forward = { forwardCalls++; true },
            gesture = { gestureCalls++; true },
        )
        assertEquals(ScrollOutcome.FORWARD_ACCEPTED, accepted)
        assertEquals(1, forwardCalls)
        assertEquals("no gesture when the a11y action succeeded", 0, gestureCalls)

        forwardCalls = 0
        val fellBack = ScrollPastGesture.execute(
            plan,
            forward = { forwardCalls++; false },
            gesture = { gestureCalls++; true },
        )
        assertEquals(ScrollOutcome.GESTURE_DISPATCHED, fellBack)
        assertEquals(1, forwardCalls)
        assertEquals(1, gestureCalls)

        val rejected = ScrollPastGesture.execute(plan, forward = { false }, gesture = { false })
        assertEquals(ScrollOutcome.GESTURE_REJECTED, rejected)
    }
}
