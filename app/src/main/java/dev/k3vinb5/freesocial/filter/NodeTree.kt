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
 * [index] is the node's position in the pre-order walk of the captured window so the service can
 * map a match back to the live `AccessibilityNodeInfo` it came from (see `NodeTreeReader`).
 */
data class NodeSnapshot(
    val viewId: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: Bounds = Bounds.EMPTY,
    val visible: Boolean = true,
    val selected: Boolean = false,
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
