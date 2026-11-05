package dev.k3vinb5.freesocial.filter

/**
 * Finds the foreground app's **Home** bottom-nav tab for [dev.k3vinb5.freesocial.core.Action.TAP_HOME_TAB].
 *
 * TAP_HOME_TAB must never act on the matched node: a rule matches the surface to hide (e.g. IG's
 * `clips_tab`), and clicking that node navigates *into* the blocked surface. The engine therefore
 * taps a different node — the real Home tab — and only falls back to `GLOBAL_ACTION_HOME` when the
 * app does not expose one (see `FilterService.perform`).
 *
 * Resolution order:
 * 1. a known resource-id for the package (from `reverse/device-dumps` bottom-nav XML);
 * 2. a clickable node whose content-description is exactly "Home" (case-insensitive);
 * 3. null → the caller performs the global Home action.
 */
object HomeTabResolver {

    private val HOME_DESCRIPTION = Regex("(?i)^home$")

    /**
     * IG: `feed_tab` carries content-desc "Home" (233005-ig_home).
     * YouTube 21.43.523 exposes no resource-ids in the feed, so only the description fallback
     * applies (the nav entry is a clickable Button with content-desc "Home", 234610-yt_home).
     * TikTok 47.6.3 obfuscates every tab id and gives its nav entries empty descriptions, so it
     * always falls through to `GLOBAL_ACTION_HOME`; no shipped TikTok rule needs TAP_HOME_TAB.
     */
    private val HOME_TAB_IDS: Map<String, List<String>> = mapOf(
        "com.instagram.android" to listOf("com.instagram.android:id/feed_tab"),
        "com.google.android.youtube" to emptyList(),
        "com.zhiliaoapp.musically" to emptyList(),
        "com.ss.android.ugc.trill" to emptyList(),
    )

    /** The node to click, or null when this window has no identifiable Home tab. */
    fun homeTab(root: NodeSnapshot, appPackage: String?): NodeSnapshot? {
        if (appPackage == null) return null
        val nodes = NodeTree.visible(NodeTree.flatten(root, NodeTree.MAX_SCAN_NODES))
        val knownIds = idsFor(appPackage)
        knownIds.firstNotNullOfOrNull { id -> nodes.firstOrNull { it.viewId == id } }?.let { return it }
        return nodes.firstOrNull {
            it.clickable && it.contentDescription?.let(HOME_DESCRIPTION::matches) == true
        }
    }

    /** D8: the known Home-tab ids for [appPackage]; also fed to the view-id graft lookup. */
    fun idsFor(appPackage: String?): List<String> =
        appPackage?.let { HOME_TAB_IDS[it] }.orEmpty()
}
