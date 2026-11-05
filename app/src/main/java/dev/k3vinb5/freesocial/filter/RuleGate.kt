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

/** The three direct surfaces that run the short-video stopwatch (SPEC §2.2). */
object BudgetSurfaces {
    private val counting: Map<String, Set<String>> = mapOf(
        AppTarget.INSTAGRAM.packageName to setOf("clips_viewer", "reels_tab"),
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

/**
 * DIRECT-ENGAGEMENT policy (2026-11-05): only a deliberate short-video surface is ever governed.
 *
 * Passive discovery — feed reel cells, Shorts shelves, anything the user merely scrolls past — is
 * never touched: no scroll, no tap, no redirect. The two retired rules stay in the asset as
 * `enabled=false` documentation. Every direct surface is budget-governed: while the allowance
 * lasts it pays the stopwatch, and once exhausted every entry is escaped.
 */
enum class SurfaceClass {
    /** A deliberate short-video surface: IG Reels viewer/tab, YT Shorts player, TikTok playback. */
    DIRECT,

    /** Feeding/scrolling chrome: never acted on. */
    PASSIVE,

    /** No direct-engagement rule claims it; the engine fails closed (does nothing). */
    UNKNOWN,
}

/** Classifies a rule's surface, independently of its JSON `layer`. */
object SurfaceClassifier {
    private val DIRECT: Map<String, Set<String>> = mapOf(
        "com.instagram.android" to setOf("clips_viewer", "reels_tab"),
        "com.google.android.youtube" to setOf("shorts_player"),
        "com.zhiliaoapp.musically" to setOf("playback"),
        "com.ss.android.ugc.trill" to setOf("playback"),
    )

    private val PASSIVE = setOf("feed_reel_cell", "shorts_shelf")

    fun classify(appPackage: String, surface: String): SurfaceClass = when {
        DIRECT[appPackage]?.contains(surface) == true -> SurfaceClass.DIRECT
        surface in PASSIVE -> SurfaceClass.PASSIVE
        else -> SurfaceClass.UNKNOWN
    }

    fun isDirect(hit: RuleHit): Boolean =
        classify(hit.rule.appPackage, hit.rule.surface) == SurfaceClass.DIRECT

    /**
     * Exact surface scoping: a capture has one surface identity, and only rules for that identity
     * (plus carve-outs) survive. A direct anchor anywhere in the tree makes it a direct surface, so
     * a feed/passive rule can never fire inside the viewer/player — and a tree with no direct anchor
     * can never be acted on by a direct rule.
     */
    fun scope(hits: List<RuleHit>): List<RuleHit> {
        val carveOuts = hits.filter { it.rule.action == Action.SKIP }
        val governed = hits.filter { it.rule.action != Action.SKIP }
        val screen = when {
            governed.any(::isDirect) -> SurfaceClass.DIRECT
            governed.any { classify(it.rule.appPackage, it.rule.surface) == SurfaceClass.PASSIVE } ->
                SurfaceClass.PASSIVE
            else -> SurfaceClass.UNKNOWN
        }
        val scoped = governed.filter { classify(it.rule.appPackage, it.rule.surface) == screen }
        return scoped + carveOuts
    }
}

/** What the engine does after one scan. */
enum class GateOutcome {
    /** Nothing to do: no direct surface, or the inspector capture paused enforcement. */
    NONE,

    /** A carve-out matched: leave the screen alone, no neutralisation at all. */
    SKIP,

    /** Direct engagement while the allowance lasts: accrue budget, perform no action. */
    PAY,

    /** Direct engagement with no allowance (or locked): run [RuleGate.chosen]'s one escape. */
    ESCAPE,
}

/**
 * The direct-engagement decision table (SPEC §2.1–2.2, policy 2026-11-05):
 * 1. any SKIP hit wins — DM/composer carve-outs beat every neutralisation;
 * 2. passive surfaces never produce an action, whatever their layer says;
 * 3. a direct surface PAYS while the budget lasts and ESCAPES once it is spent or the schedule is
 *    locked. The outcome depends only on the capture + the allowance, never on history: there is no
 *    allow window, so N entries produce N identical outcomes.
 * 4. unknown surfaces fail closed (no action).
 *
 * Exactly one action runs per scan. When several direct rules are eligible the most escape-like
 * action wins ([ACTION_PRIORITY]), so `TAP_HOME_TAB` can never be beaten by a weaker click.
 */
object RuleGate {
    /**
     * Most-escape-like first. SKIP is deliberately absent: it is a shield, handled by [decide] and
     * [chosen] before any action is picked. SCROLL_PAST is absent too: the action is retired.
     */
    private val ACTION_PRIORITY: Map<Action, Int> = mapOf(
        Action.TAP_HOME_TAB to 0,
        Action.BACK to 1,
        Action.CLICK_VIEW_ID to 2,
        Action.REDIRECT_HOME to 3,
    )

    fun decide(
        hits: List<RuleHit>,
        budgetExhausted: Boolean,
        scheduleLocked: Boolean,
        dumpArmed: Boolean = false,
    ): GateOutcome {
        if (dumpArmed) return GateOutcome.NONE
        if (hits.isEmpty()) return GateOutcome.NONE
        if (hits.any { it.rule.action == Action.SKIP }) return GateOutcome.SKIP
        if (eligibleHits(hits, budgetExhausted, scheduleLocked).isNotEmpty()) return GateOutcome.ESCAPE
        if (hits.any(::isGovernedDirectHit)) return GateOutcome.PAY
        return GateOutcome.NONE
    }

    /**
     * The single hit to execute, or null when nothing should happen. A SKIP hit shields the whole
     * scan; direct surfaces only compete once the budget is spent or the schedule is locked.
     */
    fun chosen(
        hits: List<RuleHit>,
        budgetExhausted: Boolean,
        scheduleLocked: Boolean,
        dumpArmed: Boolean = false,
    ): RuleHit? {
        if (dumpArmed) return null
        if (hits.any { it.rule.action == Action.SKIP }) return null
        return eligibleHits(hits, budgetExhausted, scheduleLocked)
            .minByOrNull { ACTION_PRIORITY.getValue(it.rule.action) }
    }

    /**
     * Direct-engagement hits only, and only when the allowance is gone. Passive and unknown
     * surfaces are never eligible; the retired SCROLL_PAST action is never eligible either.
     */
    fun eligibleHits(
        hits: List<RuleHit>,
        budgetExhausted: Boolean,
        scheduleLocked: Boolean,
    ): List<RuleHit> = hits.filter { hit ->
        isGovernedDirectHit(hit) && (budgetExhausted || scheduleLocked)
    }

    /** True when this hit is an actionable direct surface (never SKIP, never passive/unknown). */
    private fun isGovernedDirectHit(hit: RuleHit): Boolean =
        hit.rule.action != Action.SKIP &&
            hit.rule.action != Action.SCROLL_PAST &&
            SurfaceClassifier.isDirect(hit)
}

/**
 * Compiles the rule asset once and matches it against one captured tree.
 * Rules for other packages, switched-off surfaces and retired (`enabled=false`) rules are dropped.
 */
class RuleSetMatcher(
    rules: List<Rule>,
    private val toggles: SurfaceToggleReader = SurfaceToggleReader { _, _ -> true },
) {
    private data class Compiled(val rule: Rule, val matcher: RuleMatcher)

    private val compiled: List<Compiled> = rules
        .filter { it.enabled }
        .map { Compiled(it, RuleMatcher(it.match)) }

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
        val matched = compiled.mapNotNull { entry ->
            if (entry.rule.appPackage != appPackage) return@mapNotNull null
            if (!SurfacePolicy.enabled(app, entry.rule.surface, toggles)) return@mapNotNull null
            entry.matcher.match(nodes)?.let { RuleHit(entry.rule, it) }
        }
        return SurfaceClassifier.scope(matched)
    }
}
