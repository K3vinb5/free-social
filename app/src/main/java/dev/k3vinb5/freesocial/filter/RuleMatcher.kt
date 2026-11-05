package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.MatchSpec

/**
 * Evaluates a [MatchSpec] against a captured window tree.
 *
 * Criteria are AND-combined (SPEC §2.1 "multi-signal rules"): every present criterion must be
 * satisfied by some visible node, and each criterion may match a different node. A spec with no
 * criteria can never prove a match — rules fail open, so the content is left alone.
 *
 * The view-id criterion reproduces 03a `B`/`C`: an exact view-id hit (the service also tries
 * `findAccessibilityNodeInfosByViewId` directly) with a bounded deep-child walk as the fallback —
 * at most [NodeTree.VIEW_ID_LOOKUP_BUDGET] visited nodes, like the original's budget-200 walk.
 */
class RuleMatcher(
    spec: MatchSpec,
    private val visitCap: Int = NodeTree.MAX_SCAN_NODES,
) {
    private val viewId: String? = spec.viewId?.takeIf { it.isNotBlank() }
    private val textRegex: Regex? = spec.textRegex?.let(::Regex)
    private val contentDescriptionRegex: Regex? = spec.contentDescriptionRegex?.let(::Regex)
    private val classNameRegex: Regex? = spec.classNameRegex?.let(::Regex)

    val hasCriteria: Boolean
        get() = viewId != null || textRegex != null || contentDescriptionRegex != null || classNameRegex != null

    /** Convenience overload for callers holding a tree instead of a flattened list. */
    fun match(root: NodeSnapshot): NodeSnapshot? = match(NodeTree.flatten(root, visitCap))

    /**
     * Returns the anchor node of the match (the view-id node when a view id is part of the spec),
     * or null when any criterion is unsatisfied.
     */
    fun match(nodes: List<NodeSnapshot>): NodeSnapshot? {
        if (!hasCriteria) return null
        val visible = NodeTree.visible(nodes)
        if (visible.isEmpty()) return null

        var anchor: NodeSnapshot? = null

        if (viewId != null) {
            val hit = visible.asSequence()
                .take(NodeTree.VIEW_ID_LOOKUP_BUDGET)
                .firstOrNull { it.viewId == viewId }
                ?: return null
            anchor = hit
        }

        if (textRegex != null) {
            val hit = visible.firstOrNull { node -> node.text?.let(textRegex::containsMatchIn) == true }
                ?: return null
            if (anchor == null) anchor = hit
        }

        if (contentDescriptionRegex != null) {
            val hit = visible.firstOrNull { node ->
                node.contentDescription?.let(contentDescriptionRegex::containsMatchIn) == true
            } ?: return null
            if (anchor == null) anchor = hit
        }

        if (classNameRegex != null) {
            val hit = visible.firstOrNull { node ->
                node.className?.let(classNameRegex::containsMatchIn) == true
            } ?: return null
            if (anchor == null) anchor = hit
        }

        return anchor
    }
}
