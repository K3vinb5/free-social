package dev.k3vinb5.freesocial.core

/** How a rule's verified app version relates to the version actually installed. */
enum class RuleFreshness {
    /** The rules were verified on this version or a newer one — their anchors still stand. */
    FRESH,

    /** The installed app is newer than every verified stamp — anchors may have moved. */
    STALE,

    /** No honest answer: never verified, unreadable stamp, or the app is not installed. */
    UNKNOWN,
}

/**
 * Compares the app version a rule was verified against with the installed `versionName`.
 *
 * Stamps in the asset look like `"Instagram 454.0.0.54.77"` (the app name is written in front); an
 * installed `versionName` is the bare dotted string (`"460.1.2.3.4"`). Both sides are reduced to a
 * numeric segment list and compared segment by segment, a missing segment counting as zero — the
 * same "shorter is older" rule `PackageManager` uses, so `454.0.0` and `454.0.0.0` compare equal.
 *
 * Anything that cannot be compared honestly is [RuleFreshness.UNKNOWN], never a guess: no stamp (a
 * rule that was never verified), no installed version (app not installed), a version whose leading
 * token carries no number, or a qualifier in a position that would change the ordering. Trailing
 * qualifiers are tolerated (`.beta`, `54b`, `-rc1` decide on their numeric part) so vendor
 * versionNames stay usable instead of flipping the verdict.
 */
object RuleStaleness {

    /** Verdict for one rule stamp against the installed version. */
    fun compare(verifiedStamp: String?, installedVersionName: String?): RuleFreshness {
        val verified = parse(verifiedStamp) ?: return RuleFreshness.UNKNOWN
        val installed = parse(installedVersionName) ?: return RuleFreshness.UNKNOWN
        return when (compareSegments(installed, verified)) {
            Ordering.NEWER -> RuleFreshness.STALE
            Ordering.SAME, Ordering.OLDER -> RuleFreshness.FRESH
        }
    }

    /**
     * Per-app rollup: any stale rule makes the whole app stale; otherwise a single fresh rule is
     * enough to call it fresh. All-unknown (or no rules at all) stays unknown.
     */
    fun app(verifiedStamps: List<String?>, installedVersionName: String?): RuleFreshness {
        if (installedVersionName.isNullOrBlank()) return RuleFreshness.UNKNOWN
        val verdicts = verifiedStamps.map { compare(it, installedVersionName) }
        return when {
            verdicts.any { it == RuleFreshness.STALE } -> RuleFreshness.STALE
            verdicts.any { it == RuleFreshness.FRESH } -> RuleFreshness.FRESH
            else -> RuleFreshness.UNKNOWN
        }
    }

    /** The per-app warning belongs on a proven stale app only; unknown stays quiet. */
    fun showsBanner(freshness: RuleFreshness): Boolean = freshness == RuleFreshness.STALE

    /** Major segment for the copy ("... 460 is newer than ... 454"), null when unreadable. */
    fun majorVersion(version: String?): String? = parse(version)?.firstOrNull()?.toString()

    private enum class Ordering { OLDER, SAME, NEWER }

    private fun compareSegments(left: List<Int>, right: List<Int>): Ordering {
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return if (a > b) Ordering.NEWER else Ordering.OLDER
        }
        return Ordering.SAME
    }

    /**
     * `"Instagram 454.0.0.54.77"` → `[454, 0, 0, 54, 77]`; `"454.0.0.54.beta"` → `[454, 0, 0, 54]`;
     * null when the string carries no comparable numeric prefix.
     */
    private fun parse(raw: String?): List<Int>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        // A stamped version is prefixed with the app name; a bare versionName is used as-is.
        val token = text.split(WHITESPACE).lastOrNull { it.firstOrNull()?.isDigit() == true } ?: return null
        val segments = token.split('.')
        val numbers = ArrayList<Int>(segments.size)
        for ((index, segment) in segments.withIndex()) {
            val digits = segment.takeWhile { it.isDigit() }
            if (digits.isEmpty()) {
                // A pure qualifier is only tolerated as a trailing suffix; before a numeric
                // segment the shape is unreadable and no verdict is possible.
                val unreadable = segments.subList(index, segments.size).any { part -> part.any { it.isDigit() } }
                return if (unreadable) null else numbers
            }
            numbers += digits.toIntOrNull() ?: return null
        }
        return numbers.takeIf { it.isNotEmpty() }
    }

    private val WHITESPACE = Regex("\\s+")
}
