package dev.k3vinb5.freesocial.filter

/**
 * D8 layer (c) — turns a rule's text/description regex into the literal strings that can be
 * queried with `findAccessibilityNodeInfosByText`.
 *
 * The framework API only takes a plain string and does a case-insensitive *containment* match
 * (`View.findViewsWithText`), so a regex can be queried only when it degenerates to literals:
 * an optional inline flag group (`(?i)`), optional `^`/`$` anchors, optional grouping, and
 * top-level alternation of such branches. Anything containing a real metacharacter (`.`, `*`,
 * `+`, `?`, `[...]`, `{...}`, a class escape like `\d`) is **not** queryable and yields nothing —
 * that is the documented limitation; those rules still match on trees the walk can see.
 *
 * Everything here is pure string work (no `Regex` evaluation), so a hostile pattern cannot hang
 * the capture path.
 */
object RuleLookup {
    /** Single characters would graft most of the screen; two is the smallest useful anchor. */
    private const val MIN_LITERAL = 2

    private const val META = "*+?[]{}()|^$."
    private const val LITERAL_ESCAPES = "\\.*+?[]{}()|^$-/ "

    /** Every literal query a pattern can produce; de-duplicated, empty when it is a real regex. */
    fun literalQueries(pattern: String?): List<String> {
        val raw = pattern?.takeIf { it.isNotBlank() } ?: return emptyList()
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return emptyList()
        return splitAlternation(normalized).mapNotNull(::literalOf).distinct()
    }

    /** Both text fields of one spec — the anchors a text/desc rule can be queried with. */
    fun literalQueries(textRegex: String?, contentDescriptionRegex: String?): List<String> =
        (literalQueries(textRegex) + literalQueries(contentDescriptionRegex)).distinct()

    /** Drops inline flag groups `(?i)`, then any group wrapping the whole pattern, repeatedly. */
    private fun normalize(pattern: String): String {
        var current = pattern.trim()
        while (true) {
            val flags = stripFlags(current)
            val unwrapped = stripOuterGroup(flags)
            if (unwrapped == current) return current
            current = unwrapped.trim()
        }
    }

    private fun stripFlags(pattern: String): String {
        var current = pattern
        while (true) {
            if (!current.startsWith("(?")) return current
            val close = current.indexOf(')')
            if (close <= 2) return current
            val flags = current.substring(2, close)
            // Only a pure flag set ("i", "im", "i-s", …) is a flag group; "(?:" is non-capturing.
            if (flags.any { it.isLetterOrDigit() || it == '-' } &&
                flags.none { it in ":<>=" } &&
                flags.any { it == 'i' || it == 'm' || it == 's' || it == 'u' || it == 'x' || it == 'U' }
            ) {
                current = current.substring(close + 1)
            } else {
                return current
            }
        }
    }

    /** `(^shorts$| - play short$)` → `^shorts$| - play short$`; `(?:a)` → `a`. */
    private fun stripOuterGroup(pattern: String): String {
        if (pattern.length < 2 || pattern.first() != '(') return pattern
        val close = matchOfFirstOpen(pattern)
        if (close != pattern.lastIndex) return pattern
        var inner = pattern.substring(1, close)
        // `(?:…)`, `(?i:…)` etc.: drop the group header.
        if (inner.startsWith("?")) {
            val colon = inner.indexOf(':')
            if (colon < 0) return pattern
            inner = inner.substring(colon + 1)
        }
        return inner
    }

    /** Index of the parenthesis closing the one at 0, or -1 (bracket/escape aware). */
    private fun matchOfFirstOpen(pattern: String): Int {
        var depth = 0
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> i++
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** Top-level `|` only, so `(a|b)` is handled by [stripOuterGroup] first. */
    private fun splitAlternation(pattern: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> i++
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                '|' -> if (depth == 0) {
                    out += pattern.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        out += pattern.substring(start)
        return out
    }

    /** The literal a single branch represents, or null when it still contains regex syntax. */
    private fun literalOf(branch: String): String? {
        var body = branch.trim()
        // Anchors can appear after the group stripping, e.g. "^reels$".
        while (body.startsWith("^")) body = body.substring(1).trim()
        while (body.endsWith("$") && !body.endsWith("\\$")) body = body.dropLast(1).trim()
        if (body.length < MIN_LITERAL) return null
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\') {
                val next = body.getOrNull(i + 1) ?: return null
                if (next !in LITERAL_ESCAPES) return null
                out.append(next)
                i += 2
                continue
            }
            if (c in META) return null
            out.append(c)
            i++
        }
        val literal = out.toString().trim()
        return literal.takeIf { it.length >= MIN_LITERAL }
    }
}
