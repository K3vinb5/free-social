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
) {
    fun live(node: NodeSnapshot): AccessibilityNodeInfo? =
        node.index.takeIf { it in liveNodes.indices }?.let { liveNodes[it] }

    fun firstByViewId(viewId: String): AccessibilityNodeInfo? {
        val match = NodeTree.flatten(root, NodeTree.MAX_SCAN_NODES).firstOrNull { it.viewId == viewId } ?: return null
        return live(match)
    }
}

/** Converts a live node hierarchy into the pure [NodeSnapshot] form used by the matcher. */
object NodeTreeReader {
    private const val MAX_DEPTH = 64

    fun capture(root: AccessibilityNodeInfo, cap: Int = NodeTree.MAX_SCAN_NODES): CapturedTree {
        val live = ArrayList<AccessibilityNodeInfo>(minOf(cap, 64))
        val snapshot = copy(root, live, cap, 0)
            ?: NodeSnapshot(index = 0)
        return CapturedTree(snapshot, root.packageName?.toString(), live)
    }

    private fun copy(
        info: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        cap: Int,
        depth: Int,
    ): NodeSnapshot? {
        if (out.size >= cap || depth > MAX_DEPTH) return null

        val index = out.size
        out.add(info)

        val rect = Rect()
        info.getBoundsInScreen(rect)

        val children = ArrayList<NodeSnapshot>(info.childCount)
        if (depth < MAX_DEPTH) {
            for (i in 0 until info.childCount) {
                if (out.size >= cap) break
                val child = info.getChild(i) ?: continue
                copy(child, out, cap, depth + 1)?.let(children::add)
            }
        }

        return NodeSnapshot(
            viewId = info.viewIdResourceName,
            className = info.className?.toString(),
            text = info.text?.toString(),
            contentDescription = info.contentDescription?.toString(),
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            visible = info.isVisibleToUser,
            selected = info.isSelected,
            clickable = info.isClickable,
            index = index,
            children = children,
        )
    }
}
