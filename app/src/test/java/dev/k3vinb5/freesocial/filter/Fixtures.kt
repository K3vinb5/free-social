package dev.k3vinb5.freesocial.filter

import dev.k3vinb5.freesocial.core.Action
import dev.k3vinb5.freesocial.core.MatchSpec
import dev.k3vinb5.freesocial.core.Rule
import dev.k3vinb5.freesocial.settings.PrefsStore
import dev.k3vinb5.freesocial.settings.SettingsRepository

internal const val IG = "com.instagram.android"
internal const val YT = "com.google.android.youtube"
internal const val TT = "com.zhiliaoapp.musically"
internal const val TT_GLOBAL = "com.ss.android.ugc.trill"

internal fun node(
    viewId: String? = null,
    className: String? = "android.widget.FrameLayout",
    text: String? = null,
    description: String? = null,
    bounds: Bounds = Bounds(0, 0, 1080, 2400),
    visible: Boolean = true,
    selected: Boolean = false,
    clickable: Boolean = false,
    scrollable: Boolean = false,
    index: Int = 0,
    children: List<NodeSnapshot> = emptyList(),
): NodeSnapshot = NodeSnapshot(
    viewId = viewId,
    className = className,
    text = text,
    contentDescription = description,
    bounds = bounds,
    visible = visible,
    selected = selected,
    clickable = clickable,
    scrollable = scrollable,
    index = index,
    children = children,
)

internal fun rule(
    id: String = "r",
    appPackage: String = IG,
    surface: String = "surface",
    layer: Int = 2,
    match: MatchSpec = MatchSpec(viewId = "$appPackage:id/target"),
    action: Action = Action.BACK,
    viewIdTarget: String? = null,
): Rule = Rule(
    id = id,
    appPackage = appPackage,
    surface = surface,
    layer = layer,
    match = match,
    action = action,
    viewIdTarget = viewIdTarget,
)

internal fun hit(target: Rule, matched: NodeSnapshot = node(viewId = "${target.appPackage}:id/target")): RuleHit =
    RuleHit(target, matched)

internal class InMemoryPrefs : PrefsStore {
    private val booleans = mutableMapOf<String, Boolean>()
    private val ints = mutableMapOf<String, Int>()
    private val longs = mutableMapOf<String, Long>()
    private val strings = mutableMapOf<String, String>()

    override fun getBoolean(key: String, default: Boolean) = booleans[key] ?: default
    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }

    override fun getInt(key: String, default: Int) = ints[key] ?: default
    override fun putInt(key: String, value: Int) {
        ints[key] = value
    }

    override fun getLong(key: String, default: Long) = longs[key] ?: default
    override fun putLong(key: String, value: Long) {
        longs[key] = value
    }

    override fun getString(key: String) = strings[key]

    override fun putString(key: String, value: String) {
        strings[key] = value
    }
}

internal fun settings(): SettingsRepository = SettingsRepository(InMemoryPrefs())
