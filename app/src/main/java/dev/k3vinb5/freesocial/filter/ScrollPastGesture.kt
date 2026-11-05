package dev.k3vinb5.freesocial.filter

/** A screen swipe in pixels: start point → end point, used for a straight-line gesture. */
data class SwipePath(val startX: Int, val startY: Int, val endX: Int, val endY: Int)

/** Which mechanism a [ScrollPlan] should use, and what the caller ended up doing. */
enum class ScrollPath { ACTION_FORWARD, GESTURE, NONE }

/** The outcome of executing a [ScrollPlan]; the service logs success only for the first two. */
enum class ScrollOutcome { FORWARD_ACCEPTED, GESTURE_DISPATCHED, GESTURE_REJECTED, NO_PLAN }

/**
 * A resolved `SCROLL_PAST` action: the frame the gesture must be computed from, the swipe itself,
 * and the live ancestor that receives `ACTION_SCROLL_FORWARD` first.
 */
data class ScrollPlan(
    val path: ScrollPath,
    val frame: Bounds? = null,
    val frameNode: NodeSnapshot? = null,
    val swipe: SwipePath? = null,
    val reason: String? = null,
)

/**
 * Geometry and dispatch order for [dev.k3vinb5.freesocial.core.Action.SCROLL_PAST].
 *
 * The defect this replaces framed the swipe on the *matched* node: a 58 px YT shelf header became
 * a 34 px drag and an IG feed cell inside a 2020 px `RecyclerView` became a 566 px drag that the
 * list simply settled back from. The gesture is now framed on the **nearest scrollable ancestor**
 * (or, when there is none, the largest ancestor rect), spans two thirds of that container's height
 * bottom→top and the container's full width — one gesture displaces a whole post or shelf.
 *
 * Order of preference: `ACTION_SCROLL_FORWARD` on the scrollable ancestor (a `RecyclerView` honours
 * it natively and it is synchronous), and only when that reports `false` the synthetic swipe.
 * Pure so both the geometry and the ordering are unit-testable without Android.
 */
object ScrollPastGesture {
    /** Share of the scroll container's height one swipe must travel. */
    const val SPAN_FRACTION = 2f / 3f

    /** The stroke starts this far above the container's bottom edge (outside system gesture areas). */
    const val BOTTOM_INSET_FRACTION = 0.1f

    /** Horizontal inset per side, so the swipe spans (almost) the full width of the container. */
    const val EDGE_INSET_FRACTION = 0.02f

    /** Never frame a gesture on anything shorter than this; below it a drag moves nothing. */
    const val MIN_FRAME_HEIGHT_PX = 200

    private const val MIN_EDGE_INSET_PX = 4

    private val SCROLLABLE_CLASS = Regex(
        "(RecyclerView|ScrollView|ListView|GridView|ViewPager|NestedScroll|WebView)",
        RegexOption.IGNORE_CASE,
    )

    /** Resolves the plan for the node at [targetIndex] inside the capture rooted at [root]. */
    fun plan(root: NodeSnapshot, targetIndex: Int): ScrollPlan {
        val frameNode = frameNodeFor(root, targetIndex)
            ?: return ScrollPlan(ScrollPath.NONE, reason = "no ancestor frame for node $targetIndex")
        val swipe = swipePast(frameNode.bounds)
            ?: return ScrollPlan(ScrollPath.NONE, reason = "unusable frame ${frameNode.bounds}")
        return ScrollPlan(ScrollPath.ACTION_FORWARD, frameNode.bounds, frameNode, swipe)
    }

    /**
     * The node the gesture is framed on: nearest scrollable ancestor with a usable height, else the
     * largest ancestor rect. Never the matched node itself, never anything under
     * [MIN_FRAME_HEIGHT_PX].
     */
    fun frameNodeFor(root: NodeSnapshot, targetIndex: Int): NodeSnapshot? {
        val ancestors = ancestorsOf(root, targetIndex) ?: return null
        ancestors.asReversed().firstOrNull { it.isScrollContainer() && isUsableFrame(it) }?.let { return it }
        return ancestors
            .filter(::isUsableFrame)
            .maxByOrNull { it.bounds.width.toLong() * it.bounds.height.toLong() }
    }

    /** The swipe for a frame: bottom→top, two thirds of the frame height, full width. */
    fun swipePast(frame: Bounds): SwipePath? {
        if (frame.isEmpty || frame.height < MIN_FRAME_HEIGHT_PX) return null
        val insetX = maxOf((frame.width * EDGE_INSET_FRACTION).toInt(), MIN_EDGE_INSET_PX)
        val startY = frame.bottom - (frame.height * BOTTOM_INSET_FRACTION).toInt()
        val endY = startY - (frame.height * SPAN_FRACTION).toInt()
        if (endY <= frame.top) return null
        return SwipePath(frame.left + insetX, startY, frame.right - insetX, endY)
    }

    /**
     * Runs the plan: `ACTION_SCROLL_FORWARD` first, the swipe only when it reports `false`.
     * Both mechanisms are injected so the ordering is provable on the JVM.
     */
    fun execute(plan: ScrollPlan, forward: () -> Boolean, gesture: () -> Boolean): ScrollOutcome {
        if (plan.path == ScrollPath.NONE || plan.swipe == null) return ScrollOutcome.NO_PLAN
        if (forward()) return ScrollOutcome.FORWARD_ACCEPTED
        return if (gesture()) ScrollOutcome.GESTURE_DISPATCHED else ScrollOutcome.GESTURE_REJECTED
    }

    private fun isUsableFrame(node: NodeSnapshot): Boolean =
        !node.bounds.isEmpty && node.bounds.height >= MIN_FRAME_HEIGHT_PX

    private fun NodeSnapshot.isScrollContainer(): Boolean =
        scrollable || className?.let(SCROLLABLE_CLASS::containsMatchIn) == true

    /** Root..parent chain of the node with [targetIndex], or null when it is not in this capture. */
    private fun ancestorsOf(root: NodeSnapshot, targetIndex: Int): List<NodeSnapshot>? {
        val path = ArrayList<NodeSnapshot>()
        val stack = ArrayDeque<Pair<NodeSnapshot, Boolean>>()
        stack.addLast(root to false)
        while (stack.isNotEmpty()) {
            val (node, leaving) = stack.removeLast()
            if (leaving) {
                path.removeAt(path.lastIndex)
                continue
            }
            if (node.index == targetIndex && node !== root) {
                // `path` holds the node's ancestors; the node itself is not needed.
                return path.toList()
            }
            path.add(node)
            stack.addLast(node to true)
            for (i in node.children.indices.reversed()) {
                stack.addLast(node.children[i] to false)
            }
        }
        return null
    }
}
