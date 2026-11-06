package dev.k3vinb5.freesocial.filter

import android.content.Context
import android.util.Log
import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.core.RuleSet
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Loads the effective rule set: the user override at `filesDir/rules-override.json` when present
 * and valid, otherwise the shipped asset. A broken file must never take the service down — rules
 * fail open, the previous rules stay active, and the error is kept in [lastError] for the UI.
 */
object RuleCatalog {
    const val ASSET_NAME = "rules.json"
    const val TAG = "RuleCatalog"

    private val lock = Any()

    @Volatile
    private var current: List<Rule>? = null

    /** Last validation/read problem, surfaced by the Rule Inspector; null after a clean load. */
    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var source: RuleOverride.Source = RuleOverride.Source.ASSETS
        private set

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** `filesDir/rules-override.json` — the one path the user edits and imports. */
    fun overrideFile(context: Context): File = File(context.filesDir, RuleOverride.FILE_NAME)

    fun overridePath(context: Context): String = overrideFile(context).absolutePath

    /** Effective rules, loading them on first use. */
    fun current(context: Context): List<Rule> = current ?: load(context)

    /** Re-reads asset + override; safe to call on app init and on demand. */
    fun load(context: Context): List<Rule> = synchronized(lock) {
        val asset = runCatching {
            context.assets.open(ASSET_NAME).use { it.readBytes().decodeToString() }
        }.getOrNull()
        val override = runCatching {
            overrideFile(context).takeIf { it.isFile }?.readText()
        }.getOrNull()
        val selection = RuleOverride.select(asset, override, current.orEmpty())
        current = selection.rules
        source = selection.source
        lastError = selection.error
        selection.error?.let { Log.w(TAG, it) }
        if (selection.rules.isEmpty()) Log.w(TAG, "effective rules are empty — filtering fails open")
        selection.rules
    }

    sealed interface ApplyResult {
        data class Ok(val rules: List<Rule>) : ApplyResult
        data class Invalid(val error: String) : ApplyResult
        data class Failed(val error: String) : ApplyResult
    }

    /**
     * Validates [text] and, only when valid, becomes the override and reloads the live engine.
     * Invalid text leaves the active rules untouched and returns the validator's error.
     */
    fun applyOverride(context: Context, text: String): ApplyResult {
        when (val parsed = RuleOverride.parse(text)) {
            is RuleOverride.Parse.Invalid -> return ApplyResult.Invalid(parsed.error)
            is RuleOverride.Parse.Valid -> {
                val written = runCatching {
                    overrideFile(context).writeText(text)
                }
                if (written.isFailure) {
                    val error = written.exceptionOrNull()?.message ?: "cannot write override file"
                    return ApplyResult.Failed(error)
                }
                synchronized(lock) {
                    current = parsed.rules
                    source = RuleOverride.Source.OVERRIDE
                    lastError = null
                }
                RuleReload.wake()
                return ApplyResult.Ok(parsed.rules)
            }
        }
    }

    /**
     * Reverts to the shipped rules: deletes `filesDir/rules-override.json`, re-reads the asset, and
     * drops the service's compiled cache through the same [RuleReload] seam the import path uses,
     * so the built-in rules apply on the next event. Returns false only when the file cannot be
     * deleted (the override then stays active).
     */
    fun revertOverride(context: Context): Boolean {
        val file = overrideFile(context)
        val deleted = runCatching { RuleOverride.revert(file) }.getOrElse { error ->
            Log.w(TAG, "could not delete the override", error)
            false
        }
        if (!deleted) return false
        load(context)
        RuleReload.wake()
        return true
    }

    /** Import time (last write) of the override, or null when the built-in rules are active. */
    fun overrideImportedAt(context: Context): Long? = RuleOverride.importedAt(overrideFile(context))

    /** The current effective rules as a pretty-printed override document (Export / Share). */
    fun exportText(context: Context): String =
        json.encodeToString(RuleSet.serializer(), RuleSet(rules = current(context)))

    /**
     * FilterService caches the compiled matcher in its [EngineBootstrap]. Dropping that cached
     * dependency makes the next accessibility event re-run `RuleCatalog.load`, which is how an
     * applied override takes effect without an app restart. Reflection is used because the service
     * is read-only for this feature and ships unminified (release `isMinifyEnabled = false`); a
     * failure is harmless — the override still applies after the service next reconnects.
     */
    internal object RuleReload {
        fun wake(): Boolean = runCatching {
            val service = FilterService.instance ?: return@runCatching false
            val bootstrap = FilterService::class.java
                .getDeclaredField("bootstrap")
                .apply { isAccessible = true }
                .get(service) ?: return@runCatching false
            bootstrap.javaClass
                .getDeclaredField("deps")
                .apply { isAccessible = true }
                .set(bootstrap, null)
            Log.i(TAG, "engine cache dropped — the override applies on the next event")
            true
        }.getOrElse { error ->
            Log.w(TAG, "could not wake the engine cache (override applies on reconnect)", error)
            false
        }
    }
}
