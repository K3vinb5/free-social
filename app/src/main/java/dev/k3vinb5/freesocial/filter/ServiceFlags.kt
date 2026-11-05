package dev.k3vinb5.freesocial.filter

/**
 * D8 layer (a): the capture flags the service XML declares but that were evidently not in effect
 * on the runtime child links (ColorOS handed us 2–3 decor nodes while `findAccessibilityNodeInfosByViewId`
 * on the same root still reached the full tree).
 *
 * `FilterService.onServiceConnected` ORs these into `serviceInfo.flags` and calls `setServiceInfo`
 * again. The values are `AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` (0x02),
 * `FLAG_REPORT_VIEW_IDS` (0x10) and `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` (0x40); keeping the math
 * here makes it JVM-testable without touching Android.
 */
object ServiceFlags {
    /** `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | FLAG_REPORT_VIEW_IDS | FLAG_RETRIEVE_INTERACTIVE_WINDOWS`. */
    const val CAPTURE_FLAGS = 0x02 or 0x10 or 0x40

    /** Existing event/timeout flags are preserved; the capture flags are only ever added. */
    fun withCapture(current: Int): Int = current or CAPTURE_FLAGS

    /** The capture flags absent from [current]; empty after re-assertion. */
    fun missing(current: Int): Int = CAPTURE_FLAGS and current.inv()

    /** `includeNotImportantViews|reportViewIds|retrieveInteractiveWindows`, for the connect log. */
    fun describe(flags: Int): String = buildList {
        if (flags and 0x02 != 0) add("includeNotImportantViews")
        if (flags and 0x10 != 0) add("reportViewIds")
        if (flags and 0x40 != 0) add("retrieveInteractiveWindows")
    }.joinToString("|")
}
