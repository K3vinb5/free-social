package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.settings.AppTarget

/**
 * The guided-capture surface registry.
 *
 * A capture target is identified by the same `surface` slug the rule catalog uses, so a dump can
 * be checked against the exact markers the engine relies on. Rule-backed surfaces contribute their
 * rule's [MatchSpec] (enabled or retired — a retired rule is still the documentation of what the
 * surface looks like); surfaces the rules do not model (YouTube Home, IG Home without a video cell)
 * carry a small built-in marker list. Markers inside one surface are OR-combined: any of them being
 * present proves the surface is on screen.
 */
object CaptureSurfaces {

    /** Written as the dump's `screen` when no marker matched. */
    const val UNKNOWN_SURFACE = "unknown-surface"

    data class Surface(
        val app: AppTarget,
        val id: String,
        val displayName: String,
        val markers: List<MatchSpec>,
    )

    /** A dump's surface verdict: [surface] is only set when a marker actually matched. */
    data class Verdict(
        val surface: String,
        val verified: Boolean,
        val seen: List<String>,
    ) {
        val label: String get() = if (verified) surface else UNKNOWN_SURFACE
    }

    private data class Definition(val id: String, val displayName: String, val builtIn: List<MatchSpec>)

    private const val IG = "com.instagram.android"
    private const val YT = "com.google.android.youtube"
    private const val TT = "com.zhiliaoapp.musically"

    /**
     * Surfaces offered for capture, in display order. The rule markers are appended by [forApp];
     * the built-in entries exist where the surface itself is not a governed rule (Home) or where a
     * stable container id is a better detector than the action anchor.
     */
    private val DEFINITIONS: Map<AppTarget, List<Definition>> = mapOf(
        AppTarget.INSTAGRAM to listOf(
            Definition(
                id = "feed_reel_cell",
                displayName = "Home feed",
                builtIn = listOf(MatchSpec(viewId = "$IG:id/feed_tab", selected = true)),
            ),
            Definition("clips_viewer", "Reels viewer", emptyList()),
            Definition("reels_tab", "Reels tab", emptyList()),
        ),
        AppTarget.YOUTUBE to listOf(
            Definition(
                id = "home",
                displayName = "Home feed",
                builtIn = listOf(
                    MatchSpec(viewId = "$YT:id/browse_fragment_layout_coordinator_layout"),
                    MatchSpec(viewId = "$YT:id/tabs_bar"),
                ),
            ),
            Definition("shorts_player", "Shorts player", emptyList()),
        ),
        AppTarget.TIKTOK to listOf(
            Definition(
                id = "playback",
                displayName = "For You playback",
                builtIn = listOf(MatchSpec(viewId = "$TT:id/player_view")),
            ),
        ),
    )

    /** Every capture surface for [app], with the catalog's markers merged in. */
    fun forApp(app: AppTarget, rules: List<Rule>): List<Surface> =
        DEFINITIONS[app].orEmpty().map { definition ->
            Surface(
                app = app,
                id = definition.id,
                displayName = definition.displayName,
                markers = markers(app, definition.id, rules),
            )
        }

    /** OR-combined markers for one surface: built-in detectors plus every catalog rule marker. */
    fun markers(app: AppTarget, surface: String, rules: List<Rule>): List<MatchSpec> {
        val builtIn = DEFINITIONS[app].orEmpty().firstOrNull { it.id == surface }?.builtIn.orEmpty()
        val fromRules = rules
            .filter { it.appPackage == app.packageName && it.surface == surface }
            .map { it.match }
        return (builtIn + fromRules).distinct()
    }

    /** Plain-language name for a surface slug, falling back to the slug itself. */
    fun displayName(surface: String): String =
        DEFINITIONS.values.asSequence()
            .flatten()
            .firstOrNull { it.id == surface }
            ?.displayName
            ?: surface

    /**
     * Verifies one captured tree against the surface markers. Verified only when at least one
     * marker matches; otherwise the verdict carries what was actually seen so the dump says why.
     */
    fun verify(surface: String, markers: List<MatchSpec>, nodes: List<NodeSnapshot>): Verdict {
        val matched = markers.any { RuleMatcher(it).match(nodes) != null }
        return if (matched) {
            Verdict(surface = surface, verified = true, seen = emptyList())
        } else {
            Verdict(surface = surface, verified = false, seen = observed(nodes))
        }
    }

    /** Bounded, de-duplicated evidence lines: what the capture saw when no marker matched. */
    fun observed(nodes: List<NodeSnapshot>, limit: Int = 8): List<String> {
        val out = LinkedHashSet<String>()
        for (node in NodeTree.visible(nodes)) {
            val line = when {
                !node.viewId.isNullOrBlank() -> "viewId=${node.viewId}"
                !node.contentDescription.isNullOrBlank() -> "desc=\"${short(node.contentDescription)}\""
                !node.text.isNullOrBlank() -> "text=\"${short(node.text)}\""
                else -> null
            } ?: continue
            out += line
            if (out.size >= limit) break
        }
        return out.toList()
    }

    private fun short(value: String, max: Int = 60): String =
        if (value.length <= max) value else value.take(max - 1) + "…"
}
