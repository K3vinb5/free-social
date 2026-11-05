package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.core.criterionKinds
import dev.k3vinb5.freesocial.core.proofKinds

/**
 * Evaluates a [MatchSpec] against a captured window tree.
 *
 * PROOF vs HINT (rule #271): the device UI language is not fixed, so only the locale-proof
 * criteria — view id, class name, `selected` flag — decide a match. They are AND-combined
 * (SPEC §2.1 "multi-signal rules"): every present proof criterion must be satisfied by some visible
 * node, and each may match a different node. A spec with no proof criterion can never prove a
 * match, even when its localized text happens to be present: rules fail open, the content is left
 * alone.
 *
 * [MatchSpec.textRegex] / [MatchSpec.contentDescriptionRegex] are auxiliary hints. When one of them
 * matches it only *confirms* the pick (a class-only proof prefers the candidate that also carries
 * the hint); when it matches nothing — the phone speaks Portuguese, the app reworded its
 * accessibility labels — the match is unchanged. That is the "language changed" regression: a hint
 * is never allowed to kill or create a match.
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
    private val selected: Boolean? = spec.selected
    private val kinds = spec.criterionKinds
    private val proof = spec.proofKinds

    /** True when any criterion is present (hints included); see [hasProof] for matchability. */
    val hasCriteria: Boolean
        get() = kinds.isNotEmpty()

    /** True when at least one locale-proof criterion is present — the loader enforces this. */
    val hasProof: Boolean
        get() = proof.isNotEmpty()

    /** Convenience overload for callers holding a tree instead of a flattened list. */
    fun match(root: NodeSnapshot): NodeSnapshot? = match(NodeTree.flatten(root, visitCap))

    /**
     * Returns the anchor node of the match (the view-id node when a view id is part of the spec),
     * or null when any proof criterion is unsatisfied. Localized hints never cause null.
     */
    fun match(nodes: List<NodeSnapshot>): NodeSnapshot? {
        if (!hasProof) return null
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

        if (classNameRegex != null) {
            val candidates = visible.filter { node ->
                node.className?.let(classNameRegex::containsMatchIn) == true
            }
            if (candidates.isEmpty()) return null
            if (anchor == null) {
                // A class-only proof can hit several nodes (a reused container class). The hint,
                // when present, merely breaks the tie; without it the first candidate anchors.
                anchor = candidates.firstOrNull(::matchesHint) ?: candidates.first()
            }
        }

        if (selected != null) {
            val current = anchor
            if (current != null) {
                // Another criterion picked the anchor: the flag must hold on that exact node.
                if (current.selected != selected) return null
            } else {
                anchor = visible.firstOrNull { it.selected == selected } ?: return null
            }
        }

        // Hints are evaluated only to confirm a candidate above (or not at all): never a key.
        return anchor
    }

    /** True when the node carries one of the localized hint strings, if the spec has any. */
    private fun matchesHint(node: NodeSnapshot): Boolean =
        (textRegex != null && node.text?.let(textRegex::containsMatchIn) == true) ||
            (contentDescriptionRegex != null &&
                node.contentDescription?.let(contentDescriptionRegex::containsMatchIn) == true)
}
