package dev.k3vinb5.freesocial.filter

/** Pixel rect in screen coordinates. Plain data so the matcher runs on the JVM without Android. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    val isEmpty: Boolean get() = width <= 0 || height <= 0
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun intersects(other: Bounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)
    }
}

/**
 * One immutable accessibility node, as seen by the rule engine.
 *
 * [index] is the node's position in the pre-order walk of the whole capture (all windows of the
 * foreground package) so the service can map a match back to the live `AccessibilityNodeInfo` it
 * came from (see `NodeTreeReader`). [scrollable] mirrors `AccessibilityNodeInfo.isScrollable`; it
 * is what lets `SCROLL_PAST` frame its gesture on a real scroll container instead of the matched
 * (possibly tiny) node.
 */
data class NodeSnapshot(
    val viewId: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: Bounds = Bounds.EMPTY,
    val visible: Boolean = true,
    val selected: Boolean = false,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val index: Int = 0,
    val children: List<NodeSnapshot> = emptyList(),
)

/**
 * Pre-order flattening and the shared walk caps (03a §1.4): the service visits at most 400 nodes
 * per scan, and a view-id lookup only falls back to a 200-node deep walk.
 */
object NodeTree {
    /** 03a `U` visit counter: a scan gives up after 400 nodes. */
    const val MAX_SCAN_NODES = 400

    /** 03a `B`/`C`: deep child fallback lookup budget for a view id. */
    const val VIEW_ID_LOOKUP_BUDGET = 200

    /** Pre-order walk, iterative so a pathological tree cannot blow the stack. */
    fun flatten(root: NodeSnapshot, cap: Int = MAX_SCAN_NODES): List<NodeSnapshot> {
        if (cap <= 0) return emptyList()
        val out = ArrayList<NodeSnapshot>(minOf(cap, 64))
        val stack = ArrayDeque<NodeSnapshot>()
        stack.addLast(root)
        while (stack.isNotEmpty() && out.size < cap) {
            val node = stack.removeLast()
            out.add(node)
            for (i in node.children.indices.reversed()) {
                stack.addLast(node.children[i])
            }
        }
        return out
    }

    /** Nodes a rule may match: visible to the user, with real (non-empty) bounds. */
    fun visible(nodes: List<NodeSnapshot>): List<NodeSnapshot> =
        nodes.filter { it.visible && !it.bounds.isEmpty }
}

/**
 * One live accessibility node, abstracted away from Android so the capture walk (multi-window
 * aggregation, shared cap, de-duplication) is unit-testable on the JVM.
 */
interface NodeSource {
    val viewId: String?
    val className: String?
    val text: String?
    val contentDescription: String?
    val bounds: Bounds
    val visible: Boolean
    val selected: Boolean
    val clickable: Boolean
    val scrollable: Boolean
    val childCount: Int
    fun child(index: Int): NodeSource?
}

/** The result of one capture: the (synthetic) root plus the live order matching [NodeSnapshot.index]. */
class NodeTreeCapture(val root: NodeSnapshot, val live: List<NodeSource>)

/**
 * Builds one [NodeSnapshot] tree from **all** windows of the foreground package (defect D2b).
 *
 * A single window root misses surfaces that live in a second application window (TikTok's FYP when
 * a sheet or an overlay window is focused). The 400-node cap is budgeted across the windows in
 * order, and a node exposed by more than one window (same view id, class, text, description and
 * bounds) is walked once so the budget is not spent twice.
 */
object NodeTreeBuilder {
    private const val MAX_DEPTH = 64

    private data class NodeKey(
        val viewId: String?,
        val className: String?,
        val text: String?,
        val contentDescription: String?,
        val bounds: Bounds,
    )

    fun build(windows: List<NodeSource>, cap: Int = NodeTree.MAX_SCAN_NODES): NodeTreeCapture {
        if (windows.isEmpty() || cap <= 0) {
            return NodeTreeCapture(NodeSnapshot(visible = false, index = -1), emptyList())
        }
        val live = ArrayList<NodeSource>(minOf(cap, 64))
        val seen = HashSet<NodeKey>()
        val children = ArrayList<NodeSnapshot>(windows.size)
        for (window in windows) {
            if (live.size >= cap) break
            snapshot(window, live, seen, cap, 0)?.let(children::add)
        }
        return NodeTreeCapture(
            NodeSnapshot(bounds = union(windows), visible = false, index = -1, children = children),
            live,
        )
    }

    private fun snapshot(
        source: NodeSource,
        out: MutableList<NodeSource>,
        seen: MutableSet<NodeKey>,
        cap: Int,
        depth: Int,
    ): NodeSnapshot? {
        if (out.size >= cap || depth > MAX_DEPTH) return null
        val key = NodeKey(
            viewId = source.viewId,
            className = source.className,
            text = source.text,
            contentDescription = source.contentDescription,
            bounds = source.bounds,
        )
        if (!seen.add(key)) return null

        val index = out.size
        out.add(source)

        val children = ArrayList<NodeSnapshot>(source.childCount)
        if (depth < MAX_DEPTH) {
            for (i in 0 until source.childCount) {
                if (out.size >= cap) break
                val child = source.child(i) ?: continue
                snapshot(child, out, seen, cap, depth + 1)?.let(children::add)
            }
        }

        return NodeSnapshot(
            viewId = source.viewId,
            className = source.className,
            text = source.text,
            contentDescription = source.contentDescription,
            bounds = source.bounds,
            visible = source.visible,
            selected = source.selected,
            clickable = source.clickable,
            scrollable = source.scrollable,
            index = index,
            children = children,
        )
    }

    private fun union(windows: List<NodeSource>): Bounds {
        val rects = windows.map { it.bounds }.filter { !it.isEmpty }
        if (rects.isEmpty()) return Bounds.EMPTY
        return Bounds(
            left = rects.minOf { it.left },
            top = rects.minOf { it.top },
            right = rects.maxOf { it.right },
            bottom = rects.maxOf { it.bottom },
        )
    }
}
