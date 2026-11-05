package dev.k3vinb5.freesocial.filter

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * A window tree captured from a live `AccessibilityNodeInfo` hierarchy.
 * [liveNodes] is the same pre-order list the snapshots were built from, indexed by
 * [NodeSnapshot.index], so a rule match can be turned back into the real node for the action.
 */
class CapturedTree internal constructor(
    val root: NodeSnapshot,
    val packageName: String?,
    private val liveNodes: List<AccessibilityNodeInfo>,
    val windows: List<WindowStat> = emptyList(),
    val capped: Boolean = false,
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
     */
    fun captureAll(
        windows: List<WindowRoot>,
        packageName: String? = windows.firstOrNull()?.packageName,
        cap: Int = NodeTree.MAX_SCAN_NODES,
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
        val live = built.live.map { (it as AndroidNodeSource).info }
        return CapturedTree(built.root, packageName, live, built.windows, built.capped)
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
}
