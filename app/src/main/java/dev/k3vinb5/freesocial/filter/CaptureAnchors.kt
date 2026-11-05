package dev.k3vinb5.freesocial.filter

/**
 * D8 round 3 (R1) — container ids the capture grafts even though no rule matches on them.
 *
 * On the ColorOS ghost tree the YouTube Shorts shelf is invisible to the matcher for a narrower
 * reason than D8 round 2: the shelf header and every card are id-less `ViewGroup`s described only
 * through `content-desc` (`"Shorts"`, `"… – reproduzir Short"`), and
 * `findAccessibilityNodeInfosByText` returns nothing from the pruned window root while
 * `findAccessibilityNodeInfosByViewId` does reach content (`idProbe: results=2`).
 *
 * The dump `reverse/runtime-reno/pt-yt-home3.xml` (135 nodes, pt-PT) shows the shelf's nearest
 * id-bearing ancestor: `com.google.android.youtube:id/results`
 * (`android.support.v7.widget.RecyclerView`, `[0,398][1080,2158]`, `scrollable="true"`), two
 * `ViewGroup` levels above the `"Shorts"` header and the four `" – reproduzir Short"` cards.
 * Grafting that id and walking the subtree below it (`NodeTreeBuilder.graft`) puts the shelf into
 * the snapshot, so the existing desc rule matches and `SCROLL_PAST` frames on `results` itself
 * (the scrollable ancestor) instead of inventing an anchor.
 *
 * Only ids that exist in a real dump belong here. A rule's own `viewId` still comes from
 * `RuleSetMatcher.lookupViewIds`; this list is the extra container anchor needed when the matched
 * nodes carry no id at all. Provenance: docs/rules/RULES.md "Capture anchors".
 */
object CaptureAnchors {
    private const val YOUTUBE = "com.google.android.youtube"

    private val CONTAINER_IDS: Map<String, List<String>> = mapOf(
        YOUTUBE to listOf("$YOUTUBE:id/results"),
    )

    /** The container ids worth grafting for [appPackage]; empty when the platform needs none. */
    fun idsFor(appPackage: String?): List<String> =
        appPackage?.let { CONTAINER_IDS[it] }.orEmpty()
}
