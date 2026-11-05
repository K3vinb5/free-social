package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Action
import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.settings.AppTarget

/** One rule that matched on the current screen, with the node the action targets. */
data class RuleHit(val rule: Rule, val node: NodeSnapshot)

/**
 * Reads the per-surface switches out of `SettingsRepository`. Functional so the gate can be
 * exercised without Android; the service passes `settings::surfaceEnabled`.
 */
fun interface SurfaceToggleReader {
    fun enabled(app: AppTarget, key: String): Boolean
}

/** Surfaces whose rule slug is switched off by a `hide_*` toggle (SPEC §2.4 / DESIGN F7–F9). */
private val HIDE_TOGGLES = mapOf(
    "feed_reel_cell" to "hide_feed_reels",
    "reels_tab" to "hide_reels_tab",
    "shorts_shelf" to "hide_shorts_shelf",
)

/** Layer-2 surfaces whose *counting* is switched off by a `*_count_budget` toggle. */
private val COUNT_TOGGLES = mapOf(
    "shorts_player" to "shorts_count_budget",
    "playback" to "videos_count_budget",
)

/** The three layer-2 surfaces that run the short-video stopwatch (SPEC §2.2). */
object BudgetSurfaces {
    private val counting: Map<String, Set<String>> = mapOf(
        AppTarget.INSTAGRAM.packageName to setOf("clips_viewer"),
        AppTarget.YOUTUBE.packageName to setOf("shorts_player"),
        AppTarget.TIKTOK.packageName to setOf("playback"),
        "com.ss.android.ugc.trill" to setOf("playback"),
    )

    fun isCounting(appPackage: String, surface: String): Boolean =
        counting[appPackage]?.contains(surface) == true
}

/** Maps a matched rule's surface slug onto the user's per-surface switches. */
object SurfacePolicy {
    /** False when the user switched this surface off; unknown surfaces are always on. */
    fun enabled(app: AppTarget, surface: String, toggles: SurfaceToggleReader): Boolean {
        val key = HIDE_TOGGLES[surface] ?: return true
        return toggles.enabled(app, key)
    }

    /** True when this surface is one of the budgeted ones *and* the user lets it count. */
    fun countsAgainstBudget(app: AppTarget, appPackage: String, surface: String, toggles: SurfaceToggleReader): Boolean {
        if (!BudgetSurfaces.isCounting(appPackage, surface)) return false
        val key = COUNT_TOGGLES[surface] ?: return true
        return toggles.enabled(app, key)
    }
}

/** What the engine does after one scan. */
enum class GateOutcome {
    /** Nothing matched, or only budget-governed surfaces while the budget lasts. */
    NONE,

    /** A carve-out matched: leave the screen alone, no neutralisation at all. */
    SKIP,

    /** Run [RuleGate.activeHits]' first hit's action. */
    NEUTRALISE,
}

/**
 * The layer decision table (SPEC §2.1–2.2):
 * 1. any SKIP hit wins — DM/composer carve-outs beat every neutralisation;
 * 2. layer 1 is always hidden;
 * 3. layer 2 only once the budget is spent or the nightly window is locked.
 */
object RuleGate {
    fun decide(
        hits: List<RuleHit>,
        budgetExhausted: Boolean,
        scheduleLocked: Boolean,
    ): GateOutcome {
        if (hits.isEmpty()) return GateOutcome.NONE
        if (hits.any { it.rule.action == Action.SKIP }) return GateOutcome.SKIP
        if (hits.any { it.rule.layer == 1 }) return GateOutcome.NEUTRALISE
        if ((budgetExhausted || scheduleLocked) && hits.any { it.rule.layer == 2 }) return GateOutcome.NEUTRALISE
        return GateOutcome.NONE
    }

    /** The hits that justify the action: layer 1 outranks layer 2, rule-asset order otherwise. */
    fun activeHits(hits: List<RuleHit>): List<RuleHit> {
        val layerOne = hits.filter { it.rule.layer == 1 }
        return if (layerOne.isNotEmpty()) layerOne else hits
    }
}

/**
 * Compiles the rule asset once and matches it against one captured tree.
 * Rules for other packages and switched-off surfaces are dropped up front.
 */
class RuleSetMatcher(
    rules: List<Rule>,
    private val toggles: SurfaceToggleReader = SurfaceToggleReader { _, _ -> true },
) {
    private data class Compiled(val rule: Rule, val matcher: RuleMatcher)

    private val compiled: List<Compiled> = rules.map { Compiled(it, RuleMatcher(it.match)) }

    fun rulesFor(app: AppTarget, appPackage: String): List<Rule> =
        compiled.map { it.rule }.filter {
            it.appPackage == appPackage && SurfacePolicy.enabled(app, it.surface, toggles)
        }

    fun hits(app: AppTarget, appPackage: String, root: NodeSnapshot): List<RuleHit> {
        val nodes = NodeTree.flatten(root, NodeTree.MAX_SCAN_NODES)
        return compiled.mapNotNull { entry ->
            if (entry.rule.appPackage != appPackage) return@mapNotNull null
            if (!SurfacePolicy.enabled(app, entry.rule.surface, toggles)) return@mapNotNull null
            entry.matcher.match(nodes)?.let { RuleHit(entry.rule, it) }
        }
    }
}
