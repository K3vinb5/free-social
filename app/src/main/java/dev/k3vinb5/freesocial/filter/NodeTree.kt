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
 * (possibly tiny) node. [grafted] marks a node that only reachable through a view-id lookup
 * (defect D8), so diagnostics can count how much of a tree was recovered that way.
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
    val grafted: Boolean = false,
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

    /**
     * Parent in the live hierarchy, or null when the framework pruned the link. Used only by the
     * D8 graft to attach a lookup-only node under its nearest captured ancestor; never walked
     * while capturing, so the existing capture path is unchanged.
     */
    fun parent(): NodeSource? = null
}

/** One window handed to the capture walk, with the metadata the dump header reports (D6). */
data class WindowSource(
    val node: NodeSource,
    val title: String? = null,
    val className: String? = null,
    val layer: Int = 0,
    val packageName: String? = null,
)

/** Per-window capture stats: what the armed dump writes into its JSON header (D6). */
data class WindowStat(
    val title: String? = null,
    val className: String? = null,
    val layer: Int = 0,
    val packageName: String? = null,
    val nodes: Int = 0,
    val viewIds: Int = 0,
)

/**
 * The result of one capture: the (synthetic) root plus the live order matching [NodeSnapshot.index].
 * [windows] are per-window stats in capture order (top-most first); [capped] is true when the
 * 400-node budget cut the walk short; [grafts] counts nodes recovered by view-id lookup (D8).
 */
class NodeTreeCapture(
    val root: NodeSnapshot,
    val live: List<NodeSource>,
    val windows: List<WindowStat> = emptyList(),
    val capped: Boolean = false,
    val grafts: Int = 0,
)

/** A node reached by `findAccessibilityNodeInfosByViewId` although the child links never exposed it (D8). */
class GraftNode(val source: NodeSource, val lookupId: String)

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

    /**
     * D8 graft de-duplication: a live node already captured through the links has the same
     * `(viewId, bounds)` pair. Text lookup also returns id-less nodes (YouTube's Shorts shelf
     * header carries no resource id), so the key falls back to text/description for those —
     * for any node *with* an id the pair is unchanged and id-graft and text-graft still dedupe
     * against each other.
     */
    private data class LookupKey(
        val viewId: String?,
        val bounds: Bounds,
        val text: String? = null,
        val contentDescription: String? = null,
    )

    private fun lookupKey(source: NodeSource): LookupKey =
        LookupKey(source.viewId, source.bounds, source.text, source.contentDescription)

    private fun lookupKey(node: NodeSnapshot): LookupKey =
        LookupKey(node.viewId, node.bounds, node.text, node.contentDescription)

    fun build(windows: List<NodeSource>, cap: Int = NodeTree.MAX_SCAN_NODES): NodeTreeCapture =
        buildWindows(windows.map { WindowSource(it) }, cap)

    /**
     * Aggregates [windows] top-most first (higher `layer` wins; the order is stable so callers may
     * hand in `getWindows()`'s z-order unchanged). Nodes with a null viewId are valid and kept: only
     * the walk cap ever drops a node.
     */
    fun buildWindows(windows: List<WindowSource>, cap: Int = NodeTree.MAX_SCAN_NODES): NodeTreeCapture {
        if (windows.isEmpty() || cap <= 0) {
            return NodeTreeCapture(NodeSnapshot(visible = false, index = -1), emptyList())
        }
        val ordered = windows.sortedByDescending { it.layer }
        val live = ArrayList<NodeSource>(minOf(cap, 64))
        val seen = HashSet<NodeKey>()
        val stats = ArrayList<WindowStat>(ordered.size)
        val children = ArrayList<NodeSnapshot>(ordered.size)
        val capped = booleanArrayOf(false)
        for (window in ordered) {
            if (live.size >= cap) {
                capped[0] = true
                break
            }
            val before = live.size
            snapshot(window.node, live, seen, cap, 0, capped)?.let(children::add)
            val slice = live.subList(before, live.size)
            stats += WindowStat(
                title = window.title,
                className = window.className ?: window.node.className,
                layer = window.layer,
                packageName = window.packageName,
                nodes = slice.size,
                viewIds = slice.count { it.viewId != null },
            )
        }
        return NodeTreeCapture(
            NodeSnapshot(bounds = union(ordered.map { it.node }), visible = false, index = -1, children = children),
            live,
            stats,
            capped[0],
        )
    }

    private fun snapshot(
        source: NodeSource,
        out: MutableList<NodeSource>,
        seen: MutableSet<NodeKey>,
        cap: Int,
        depth: Int,
        capped: BooleanArray,
    ): NodeSnapshot? {
        if (out.size >= cap) {
            capped[0] = true
            return null
        }
        if (depth > MAX_DEPTH) return null
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
                if (out.size >= cap) {
                    capped[0] = true
                    break
                }
                val child = source.child(i) ?: continue
                snapshot(child, out, seen, cap, depth + 1, capped)?.let(children::add)
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

    /**
     * D8 layers (b) and (c): splices lookup-only nodes into a capture without touching the matcher.
     *
     * Each [GraftNode] is skipped when its live node was already walked (same `(viewId, bounds)`;
     * id-less nodes are distinguished by text/description), so a node reachable both through the
     * child links, the view-id lookup and the text lookup appears exactly once. A graft is linked
     * under its nearest ancestor that is already in the snapshot (parent chain matched the same
     * way); when the pruned hierarchy exposes no such ancestor it becomes a "lookup-graft" child
     * of the top-most window root (the synthetic root only when the capture has no window).
     * Grafted nodes are appended to [NodeTreeCapture.live] at their own index, so actions still hit
     * the live node. The shared [cap] is respected and a dropped graft marks the capture capped.
     */
    fun graft(
        base: NodeTreeCapture,
        grafts: List<GraftNode>,
        cap: Int = NodeTree.MAX_SCAN_NODES,
    ): NodeTreeCapture {
        if (grafts.isEmpty() || cap <= 0) return base
        val captured = NodeTree.flatten(base.root, cap)
        val byKey = HashMap<LookupKey, NodeSnapshot>(captured.size)
        for (node in captured) byKey[lookupKey(node)] = node
        val live = ArrayList<NodeSource>(base.live)
        val underParent = HashMap<Int, MutableList<NodeSnapshot>>()
        val underRoot = ArrayList<NodeSnapshot>()
        // Fallback parent: the top-most captured window root (the brief's "under the window root");
        // only a window-less capture needs the synthetic root itself.
        val windowRootIndex = base.root.children.firstOrNull()?.index
        var added = 0
        var capped = base.capped
        for (graft in grafts) {
            if (live.size >= cap) {
                capped = true
                break
            }
            val key = lookupKey(graft.source)
            if (byKey.containsKey(key)) continue
            val index = live.size
            val snapshot = NodeSnapshot(
                viewId = graft.source.viewId,
                className = graft.source.className,
                text = graft.source.text,
                contentDescription = graft.source.contentDescription,
                bounds = graft.source.bounds,
                visible = graft.source.visible,
                selected = graft.source.selected,
                clickable = graft.source.clickable,
                scrollable = graft.source.scrollable,
                index = index,
                grafted = true,
            )
            live += graft.source
            byKey[key] = snapshot
            val parent = capturedAncestor(graft.source, byKey) ?: windowRootIndex
            if (parent == null) underRoot += snapshot
            else underParent.getOrPut(parent) { ArrayList() } += snapshot
            added++
        }
        if (added == 0) {
            return if (capped == base.capped) base
            else NodeTreeCapture(base.root, base.live, base.windows, capped, base.grafts)
        }
        val root = base.root.copy(children = rebuild(base.root.children, underParent) + underRoot)
        return NodeTreeCapture(root, live, base.windows, capped, base.grafts + added)
    }

    /** Index of the nearest captured ancestor, walking the live parent chain (link-pruned → null). */
    private fun capturedAncestor(source: NodeSource, byKey: Map<LookupKey, NodeSnapshot>): Int? {
        var parent = source.parent()
        var depth = 0
        while (parent != null && depth < MAX_DEPTH) {
            byKey[lookupKey(parent)]?.let { return it.index }
            parent = parent.parent()
            depth++
        }
        return null
    }

    /** Returns [children] unchanged when nothing hangs below it, so untouched subtrees stay shared. */
    private fun rebuild(
        children: List<NodeSnapshot>,
        underParent: Map<Int, List<NodeSnapshot>>,
    ): List<NodeSnapshot> {
        if (children.isEmpty()) return children
        val out = ArrayList<NodeSnapshot>(children.size)
        var changed = false
        for (child in children) {
            val rebuiltChildren = rebuild(child.children, underParent)
            val extra = underParent[child.index].orEmpty()
            if (rebuiltChildren !== child.children || extra.isNotEmpty()) {
                changed = true
                out += child.copy(children = rebuiltChildren + extra)
            } else {
                out += child
            }
        }
        return if (changed) out else children
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
