package dev.k3vinb5.freesocial.filter

import android.content.Context
import android.util.Log
import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.core.loadRuleSet

/**
 * Loads the shipped rule asset. A broken asset must never take the service down: rules fail open,
 * so the engine simply runs with an empty set and logs the authoring mistake.
 */
object RuleCatalog {
    const val ASSET_NAME = "rules.json"
    private const val TAG = "RuleCatalog"

    fun load(context: Context): List<Rule> = try {
        val text = context.assets.open(ASSET_NAME).use { it.readBytes().decodeToString() }
        loadRuleSet(text).rules
    } catch (t: Throwable) {
        Log.w(TAG, "rule asset unavailable — filtering fails open", t)
        emptyList()
    }
}
