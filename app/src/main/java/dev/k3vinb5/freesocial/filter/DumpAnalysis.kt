package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.core.Rule

/** One enabled rule that matches a saved dump, with the anchor evidence the user can check. */
data class DumpRuleMatch(
    val rule: Rule,
    val anchors: List<String>,
)

/** Static provenance stamps copied from `docs/rules/RULES.md` (shown when a rule has no stamp). */
object RuleProvenance {
    private val VERSIONS: Map<String, String> = mapOf(
        "ig_feed_reel_cell" to "Instagram 454.0.0.54.77",
        "ig_reels_tab" to "Instagram 454.0.0.54.77",
        "ig_clips_viewer" to "Instagram 454.0.0.54.77",
        "ig_dm_carve_out" to "Instagram 454.0.0.54.77",
        "yt_shorts_shelf" to "YouTube 21.43.523",
        "yt_shorts_player" to "YouTube 21.43.523",
        "tiktok_playback" to "TikTok 47.6.3",
        "tiktok_dm_carve_out" to "TikTok 47.6.3",
    )

    /** The rule's own stamp wins; the static RULES.md map is the fallback for un-stamped rules. */
    fun versionFor(rule: Rule): String? =
        rule.verifiedAgainstAppVersion?.takeIf { it.isNotBlank() } ?: VERSIONS[rule.id]
}

/**
 * Offline "what would the engine do with this dump?" analysis for the Rule Inspector.
 *
 * Pure and engine-free: it runs the existing [RuleMatcher] over the dump's exported nodes, so the
 * inspector shows exactly the matching rules and anchors without touching the live service.
 */
object DumpAnalysis {

    /** Enabled rules for the dump's package that match its nodes, with anchor evidence lines. */
    fun matches(nodes: List<DumpNode>, rules: List<Rule>, appPackage: String): List<DumpRuleMatch> {
        if (nodes.isEmpty()) return emptyList()
        val snapshots = nodes.mapIndexed { index, node -> snapshot(node, index) }
        return rules
            .filter { it.enabled && it.appPackage == appPackage }
            .mapNotNull { rule ->
                val anchor = RuleMatcher(rule.match).match(snapshots) ?: return@mapNotNull null
                DumpRuleMatch(rule = rule, anchors = anchors(rule.match, snapshots, anchor))
            }
    }

    /** One exported node as a pure snapshot; an unparsable bounds string keeps the node visible. */
    fun snapshot(node: DumpNode, index: Int = 0): NodeSnapshot = NodeSnapshot(
        viewId = node.viewId,
        className = node.className,
        text = node.text,
        contentDescription = node.contentDescription,
        bounds = parseBounds(node.bounds),
        visible = node.visible,
        selected = node.selected,
        clickable = node.clickable,
        index = index,
        grafted = node.grafted,
    )

    /** `[l,t][r,b]` as exported by [DumpExporter]; empty/invalid → a non-empty fallback rect. */
    fun parseBounds(text: String): Bounds {
        val match = BOUNDS.matchEntire(text.trim())
        if (match == null) return Bounds(0, 0, 1, 1)
        val (l, t, r, b) = match.destructured
        return Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    /**
     * Per-criterion anchor lines (viewId/text/desc/class/selected). Each criterion is resolved to
     * its own first matching visible node, so a match spread over several nodes is fully shown.
     */
    fun anchors(spec: MatchSpec, nodes: List<NodeSnapshot>, anchor: NodeSnapshot): List<String> {
        val visible = NodeTree.visible(nodes)
        val lines = LinkedHashSet<String>()
        spec.viewId?.takeIf { it.isNotBlank() }?.let { id ->
            if (visible.any { it.viewId == id }) lines += "viewId=$id"
        }
        spec.textRegex?.let { pattern ->
            val regex = Regex(pattern)
            visible.firstOrNull { it.text?.let(regex::containsMatchIn) == true }
                ?.text?.let { lines += "text=\"${short(it)}\"" }
        }
        spec.contentDescriptionRegex?.let { pattern ->
            val regex = Regex(pattern)
            visible.firstOrNull { it.contentDescription?.let(regex::containsMatchIn) == true }
                ?.contentDescription?.let { lines += "desc=\"${short(it)}\"" }
        }
        spec.classNameRegex?.let { pattern ->
            val regex = Regex(pattern)
            visible.firstOrNull { it.className?.let(regex::containsMatchIn) == true }
                ?.className?.let { lines += "class=$it" }
        }
        spec.selected?.let { flag ->
            if (anchor.selected == flag) lines += "selected=$flag"
        }
        if (lines.isEmpty()) {
            anchor.viewId?.let { lines += "viewId=$it" }
            anchor.contentDescription?.let { lines += "desc=\"${short(it)}\"" }
        }
        return lines.toList()
    }

    private fun short(value: String, max: Int = 70): String =
        if (value.length <= max) value else value.take(max - 1) + "…"
}
