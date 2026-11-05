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

    /** Run [RuleGate.chosen]'s action. */
    NEUTRALISE,
}

/**
 * The layer decision table (SPEC §2.1–2.2):
 * 1. any SKIP hit wins — DM/composer carve-outs beat every neutralisation;
 * 2. layer 1 is always hidden;
 * 3. layer 2 only once the budget is spent or the nightly window is locked.
 *
 * Exactly one action runs per scan. When several rules are eligible the most escape-like action
 * wins ([ACTION_PRIORITY]), so a layer-1 tab hit can never leave a layer-2 viewer playing:
 * leaving the surface (Home tab / Back / swipe) always beats clicking and beats the app-wide
 * Home redirect.
 */
object RuleGate {
    /**
     * Most-escape-like first. SKIP is deliberately absent: it is a shield, handled by [decide] and
     * [chosen] before any action is picked.
     */
    private val ACTION_PRIORITY: Map<Action, Int> = mapOf(
        Action.TAP_HOME_TAB to 0,
        Action.BACK to 1,
        Action.SCROLL_PAST to 2,
        Action.CLICK_VIEW_ID to 3,
        Action.REDIRECT_HOME to 4,
    )

    fun decide(
        hits: List<RuleHit>,
        budgetExhausted: Boolean,
        scheduleLocked: Boolean,
    ): GateOutcome {
        if (hits.isEmpty()) return GateOutcome.NONE
        if (hits.any { it.rule.action == Action.SKIP }) return GateOutcome.SKIP
        if (eligibleHits(hits, budgetExhausted, scheduleLocked).isNotEmpty()) return GateOutcome.NEUTRALISE
        return GateOutcome.NONE
    }

    /**
     * The single hit to execute, or null when nothing should happen. A SKIP hit shields the whole
     * scan (the service still stops the stopwatch); layer 2 only competes once the budget is spent
     * or the schedule is locked.
     */
    fun chosen(
        hits: List<RuleHit>,
        budgetExhausted: Boolean,
        scheduleLocked: Boolean,
    ): RuleHit? {
        if (hits.any { it.rule.action == Action.SKIP }) return null
        return eligibleHits(hits, budgetExhausted, scheduleLocked)
            .minByOrNull { ACTION_PRIORITY.getValue(it.rule.action) }
    }

    /** Layer-1 hits plus, only when gated open, layer-2 hits. SKIP never yields an action. */
    fun eligibleHits(
        hits: List<RuleHit>,
        budgetExhausted: Boolean,
        scheduleLocked: Boolean,
    ): List<RuleHit> = hits.filter { hit ->
        val rule = hit.rule
        rule.action != Action.SKIP && when (rule.layer) {
            1 -> true
            2 -> budgetExhausted || scheduleLocked
            else -> false
        }
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

    /**
     * D8 layer (b): the view ids the applicable rules actually declare — match ids and explicit
     * click targets. Only these are worth a `findAccessibilityNodeInfosByViewId` lookup, because
     * unknown ids cannot be enumerated.
     */
    fun lookupViewIds(app: AppTarget, appPackage: String): List<String> =
        rulesFor(app, appPackage)
            .flatMap { rule -> listOfNotNull(rule.match.viewId, rule.viewIdTarget) }
            .filter { it.isNotBlank() }
            .distinct()

    /**
     * D8 layer (c): the literal text / content-description values the applicable rules can be
     * queried with, derived from their existing regexes ([RuleLookup]) — `rules.json`, [Rule] and
     * `MatchSpec` stay unchanged. A real regex contributes nothing: it cannot be asked of
     * `findAccessibilityNodeInfosByText`.
     */
    fun lookupTexts(app: AppTarget, appPackage: String): List<String> =
        rulesFor(app, appPackage)
            .flatMap { rule ->
                RuleLookup.literalQueries(rule.match.textRegex, rule.match.contentDescriptionRegex)
            }
            .distinct()

    fun hits(app: AppTarget, appPackage: String, root: NodeSnapshot): List<RuleHit> {
        val nodes = NodeTree.flatten(root, NodeTree.MAX_SCAN_NODES)
        return compiled.mapNotNull { entry ->
            if (entry.rule.appPackage != appPackage) return@mapNotNull null
            if (!SurfacePolicy.enabled(app, entry.rule.surface, toggles)) return@mapNotNull null
            entry.matcher.match(nodes)?.let { RuleHit(entry.rule, it) }
        }
    }
}
