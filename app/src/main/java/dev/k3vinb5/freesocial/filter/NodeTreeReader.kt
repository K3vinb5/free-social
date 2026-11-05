package dev.k3vinb5.freesocial.filter

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * A window tree captured from a live `AccessibilityNodeInfo` hierarchy.
 * [liveNodes] is the same pre-order list the snapshots were built from, indexed by
 * [NodeSnapshot.index], so a rule match can be turned back into the real node for the action.
 * [grafts] is how many nodes the D8 lookups (view-id and text) recovered that the child links
 * never exposed.
 */
class CapturedTree internal constructor(
    val root: NodeSnapshot,
    val packageName: String?,
    private val liveNodes: List<AccessibilityNodeInfo>,
    val windows: List<WindowStat> = emptyList(),
    val capped: Boolean = false,
    val grafts: Int = 0,
) {
    fun live(node: NodeSnapshot): AccessibilityNodeInfo? =
        node.index.takeIf { it in liveNodes.indices }?.let { liveNodes[it] }

    fun firstByViewId(viewId: String): AccessibilityNodeInfo? {
        val match = NodeTree.flatten(root, NodeTree.MAX_SCAN_NODES).firstOrNull { it.viewId == viewId } ?: return null
        return live(match)
    }
}

/** Converts live node hierarchies into the pure [NodeSnapshot] form used by the matcher. */
object NodeTreeReader {

    fun capture(root: AccessibilityNodeInfo, cap: Int = NodeTree.MAX_SCAN_NODES): CapturedTree =
        captureWindows(listOf(root), root.packageName?.toString(), cap)

    /**
     * Captures **every** window the foreground package owns in one tree (defect D2b): TikTok and
     * Instagram can run a second application window (sheet, overlay) and keying on the focused
     * window alone scans the wrong tree. The 400-node cap is shared across the windows and a node
     * exposed by two windows is walked once (see [NodeTreeBuilder]).
     */
    fun captureWindows(
        roots: List<AccessibilityNodeInfo>,
        packageName: String? = roots.firstOrNull()?.packageName?.toString(),
        cap: Int = NodeTree.MAX_SCAN_NODES,
    ): CapturedTree = captureAll(roots.map { WindowRoot(it, packageName = packageName) }, packageName, cap)

    /**
     * D6: captures every window of the target package with the metadata the armed dump reports in
     * its header. [WindowRoot]s are aggregated top-most first by [NodeTreeBuilder.buildWindows].
     *
     * D8 layer (b): [viewIdLookups] are queried with `findAccessibilityNodeInfosByViewId` on each
     * window root and the returned nodes are grafted into the snapshot. Only ids the rules (or the
     * engine's known ids) declare are queried — unknown ids cannot be enumerated — so this is the
     * guaranteed fallback when the framework hands us pruned child links. Ids in [subtreeLookups]
     * (the [CaptureAnchors] containers, R1) additionally walk their children, because the matched
     * nodes below them carry no id at all.
     *
     * D8 layer (c): [textLookups] are the literal text/description strings derived from the
     * applicable rules ([RuleLookup]) and queried with `findAccessibilityNodeInfosByText` on the
     * same roots. That is what makes the text/desc-anchored rules (YouTube Shorts shelf, TikTok
     * playback chrome) work on a platform that prunes child links but keeps the framework's own
     * search index intact. Both graft kinds share one de-duplication map and one node cap.
     */
    fun captureAll(
        windows: List<WindowRoot>,
        packageName: String? = windows.firstOrNull()?.packageName,
        cap: Int = NodeTree.MAX_SCAN_NODES,
        viewIdLookups: List<String> = emptyList(),
        textLookups: List<String> = emptyList(),
        subtreeLookups: List<String> = emptyList(),
    ): CapturedTree {
        val sources = windows.map { window ->
            WindowSource(
                node = AndroidNodeSource(window.node),
                title = window.title,
                className = window.className ?: window.node.className?.toString(),
                layer = window.layer,
                packageName = window.packageName,
            )
        }
        val built = NodeTreeBuilder.buildWindows(sources, cap)
        val grafts = if (viewIdLookups.isEmpty() && textLookups.isEmpty()) emptyList()
        else {
            lookupGrafts(windows, viewIdLookups, subtreeLookups.toSet(), cap) +
                textGrafts(windows, textLookups, cap)
        }
        val capture = if (grafts.isEmpty()) built else NodeTreeBuilder.graft(built, grafts, cap)
        val live = capture.live.map { (it as AndroidNodeSource).info }
        return CapturedTree(capture.root, packageName, live, capture.windows, capture.capped, capture.grafts)
    }

    /**
     * The live nodes `findAccessibilityNodeInfosByViewId` reaches for the declared ids, across every
     * window root. De-duplication (a node reachable both via links and lookup) happens in
     * [NodeTreeBuilder.graft]; the lookup itself is bounded by [cap].
     */
    private fun lookupGrafts(
        windows: List<WindowRoot>,
        ids: List<String>,
        subtreeIds: Set<String>,
        cap: Int,
    ): List<GraftNode> {
        val out = ArrayList<GraftNode>()
        for (window in windows) {
            for (id in ids) {
                if (out.size >= cap) return out
                val found = runCatching { window.node.findAccessibilityNodeInfosByViewId(id) }
                    .getOrNull()
                    ?: continue
                for (info in found) {
                    if (info == null) continue
                    if (out.size >= cap) return out
                    val expected = window.packageName
                    if (expected != null && info.packageName?.toString() != expected) continue
                    out += GraftNode(AndroidNodeSource(info), id, subtree = id in subtreeIds)
                }
            }
        }
        return out
    }

    /**
     * D8 layer (c): the live nodes `findAccessibilityNodeInfosByText` reaches for each literal
     * rule value. The framework matches case-insensitively on text *and* content description
     * (`View.findViewsWithText` compares both), so a desc-only rule works through the same call.
     * Bounded by [cap]; [NodeTreeBuilder.graft] de-duplicates against the walk and the id grafts.
     */
    private fun textGrafts(windows: List<WindowRoot>, values: List<String>, cap: Int): List<GraftNode> {
        val out = ArrayList<GraftNode>()
        for (window in windows) {
            for (value in values) {
                if (out.size >= cap) return out
                val found = runCatching { window.node.findAccessibilityNodeInfosByText(value) }
                    .getOrNull()
                    ?: continue
                for (info in found) {
                    if (info == null) continue
                    if (out.size >= cap) return out
                    val expected = window.packageName
                    if (expected != null && info.packageName?.toString() != expected) continue
                    out += GraftNode(AndroidNodeSource(info), value)
                }
            }
        }
        return out
    }
}

/** One live application window of the target package, with its title/class/layer for the dump header. */
class WindowRoot(
    val node: AccessibilityNodeInfo,
    val title: String? = null,
    val className: String? = null,
    val layer: Int = 0,
    val packageName: String? = null,
)

/** [NodeSource] over one live accessibility node; [bounds] is read on each access. */
private class AndroidNodeSource(val info: AccessibilityNodeInfo) : NodeSource {
    private val rect = Rect()

    override val viewId: String? get() = info.viewIdResourceName
    override val className: String? get() = info.className?.toString()
    override val text: String? get() = info.text?.toString()
    override val contentDescription: String? get() = info.contentDescription?.toString()

    override val bounds: Bounds
        get() {
            info.getBoundsInScreen(rect)
            return Bounds(rect.left, rect.top, rect.right, rect.bottom)
        }

    override val visible: Boolean get() = info.isVisibleToUser
    override val selected: Boolean get() = info.isSelected
    override val clickable: Boolean get() = info.isClickable
    override val scrollable: Boolean get() = info.isScrollable
    override val childCount: Int get() = info.childCount

    override fun child(index: Int): NodeSource? = info.getChild(index)?.let(::AndroidNodeSource)

    override fun parent(): NodeSource? = info.parent?.let(::AndroidNodeSource)
}
