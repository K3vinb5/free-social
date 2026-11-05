package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.core.loadRuleSet

/**
 * User-editable rule override (`filesDir/rules-override.json`).
 *
 * The override uses the same JSON schema and the same loader validation as the shipped asset and
 * **replaces** it wholesale when valid. A rejected override never takes filtering down: the current
 * effective rules stay active and the validation error is surfaced to the caller.
 *
 * Pure string work on purpose, so replace/fallback semantics are JVM-testable.
 */
object RuleOverride {

    const val FILE_NAME = "rules-override.json"

    enum class Source { ASSETS, OVERRIDE }

    data class Selection(
        val rules: List<Rule>,
        val source: Source,
        val error: String?,
    )

    sealed interface Parse {
        data class Valid(val rules: List<Rule>) : Parse
        data class Invalid(val error: String) : Parse
    }

    /** Parses one rule document with the production loader (schema + semantic validation). */
    fun parse(text: String): Parse = try {
        Parse.Valid(loadRuleSet(text).rules)
    } catch (t: Throwable) {
        Parse.Invalid(t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName)
    }

    /**
     * Effective rules for the service: an existing override wins when it validates; an invalid
     * override falls back to the asset, and a broken asset falls back to [fallback] (the rules
     * already active) while reporting the error.
     */
    fun select(
        assetText: String?,
        overrideText: String?,
        fallback: List<Rule> = emptyList(),
    ): Selection {
        val asset = assetText?.let(::parse)
        val assetRules = (asset as? Parse.Valid)?.rules

        if (overrideText != null) {
            when (val override = parse(overrideText)) {
                is Parse.Valid -> return Selection(override.rules, Source.OVERRIDE, null)
                is Parse.Invalid -> return Selection(
                    rules = assetRules ?: fallback,
                    source = Source.ASSETS,
                    error = "override rejected: ${override.error}",
                )
            }
        }

        return when (asset) {
            is Parse.Valid -> Selection(asset.rules, Source.ASSETS, null)
            is Parse.Invalid -> Selection(
                rules = fallback,
                source = Source.ASSETS,
                error = "rule asset rejected: ${asset.error}",
            )
            null -> Selection(
                rules = fallback,
                source = Source.ASSETS,
                error = if (fallback.isEmpty()) "rule asset unavailable" else null,
            )
        }
    }
}
