package dev.k3vinb5.freesocial.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A parsed rule asset: `{ "schemaVersion": 1, "rules": [ ... ] }`. */
@Serializable
data class RuleSet(
    val schemaVersion: Int = SCHEMA_VERSION,
    val rules: List<Rule> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * One content-filtering rule.
 *
 * [layer] 1 = always hidden (passive discovery), 2 = budget-governed (intentional consumption).
 * [viewIdTarget] is only meaningful for [Action.CLICK_VIEW_ID].
 */
@Serializable
data class Rule(
    val id: String,
    val appPackage: String,
    val surface: String,
    val layer: Int,
    val match: MatchSpec,
    val action: Action,
    val viewIdTarget: String? = null,
    val notes: String? = null,
    val verifiedAgainstAppVersion: String? = null,
)

/** AND-combined optional criteria; at least one must be present. */
@Serializable
data class MatchSpec(
    val viewId: String? = null,
    val textRegex: String? = null,
    val contentDescriptionRegex: String? = null,
    val classNameRegex: String? = null,
) {
    val isEmpty: Boolean
        get() = listOfNotNull(viewId, textRegex, contentDescriptionRegex, classNameRegex).isEmpty()
}

@Serializable
enum class Action {
    BACK,
    TAP_HOME_TAB,
    CLICK_VIEW_ID,
    REDIRECT_HOME,
    /** DM carve-out: leave the screen alone. */
    SKIP,
}

class RuleSetException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

private val json = Json { ignoreUnknownKeys = false }

/** Parses and validates a rule asset. Throws [RuleSetException] on any authoring mistake. */
fun loadRuleSet(jsonText: String): RuleSet {
    val set = try {
        json.decodeFromString<RuleSet>(jsonText)
    } catch (e: SerializationException) {
        throw RuleSetException("malformed rule set: ${e.message}", e)
    }
    validate(set)
    return set
}

private fun validate(set: RuleSet) {
    if (set.schemaVersion != RuleSet.SCHEMA_VERSION) {
        throw RuleSetException("unsupported schemaVersion ${set.schemaVersion}")
    }
    val seen = mutableSetOf<Pair<String, String>>()
    for (rule in set.rules) {
        if (rule.id.isBlank()) throw RuleSetException("rule with blank id")
        if (rule.layer != 1 && rule.layer != 2) {
            throw RuleSetException("rule '${rule.id}': layer must be 1 or 2, was ${rule.layer}")
        }
        if (rule.match.isEmpty) {
            throw RuleSetException("rule '${rule.id}': match needs at least one criterion")
        }
        rule.match.viewId?.let { if (it.isBlank()) throw RuleSetException("rule '${rule.id}': blank viewId") }
        rule.match.textRegex?.let { requireRegex(rule.id, "textRegex", it) }
        rule.match.contentDescriptionRegex?.let { requireRegex(rule.id, "contentDescriptionRegex", it) }
        rule.match.classNameRegex?.let { requireRegex(rule.id, "classNameRegex", it) }
        if (rule.action == Action.CLICK_VIEW_ID && rule.viewIdTarget.isNullOrBlank()) {
            throw RuleSetException("rule '${rule.id}': CLICK_VIEW_ID needs viewIdTarget")
        }
        if (!seen.add(rule.appPackage to rule.surface)) {
            throw RuleSetException("duplicate rule for ${rule.appPackage} / ${rule.surface}")
        }
    }
}

private fun requireRegex(ruleId: String, field: String, pattern: String) {
    try {
        Regex(pattern)
    } catch (e: Exception) {
        throw RuleSetException("rule '$ruleId': invalid $field '$pattern'", e)
    }
}
