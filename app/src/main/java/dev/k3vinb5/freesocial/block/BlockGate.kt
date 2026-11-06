package dev.k3vinb5.freesocial.block

import dev.k3vinb5.freesocial.settings.AppMode
import dev.k3vinb5.freesocial.settings.AppTarget

/** Why the block screen is up. Localised text lives with the UI, not in the gate. */
enum class BlockReason {
    /** Nightly lock window is active (SPEC §2.3). Wins over the per-app reason. */
    SCHEDULE,

    /** The app's mode is Block and the schedule is clear. */
    APP_BLOCK,
}

/** Result of the whole-app gate chain: either nothing happens or we show the block screen. */
sealed interface BlockDecision {
    data object Ignore : BlockDecision

    data class Launch(
        val platform: String,
        val reason: BlockReason,
        val packageName: String,
    ) : BlockDecision
}

/**
 * Simplified v1 whole-app gate chain (SPEC §2.3; `docs/analysis/README.md` (RE notes 01a) gates 0, 2, 3, 10, 14, 15).
 *
 * Pure and Android-free: the service feeds device flags plus the resolved target/mode, and the JVM
 * tests drive the decision table with a fake clock.
 */
class BlockGate(
    private val ownPackage: String = DEFAULT_OWN_PACKAGE,
    private val dedupeWindowMillis: Long = DEFAULT_DEDUPE_MILLIS,
) {
    /** package -> last launch time. In-memory only, like the original's map. */
    private val lastLaunchAt = HashMap<String, Long>()

    fun decide(
        packageName: String?,
        target: AppTarget?,
        mode: AppMode?,
        deviceInteractive: Boolean,
        keyguardLocked: Boolean,
        scheduleLocked: Boolean,
        nowMillis: Long,
        allowedPackages: Set<String> = emptySet(),
    ): BlockDecision {
        // Gate 0 — screen off / locked (deliberate, reproduced).
        if (!deviceInteractive || keyguardLocked) return BlockDecision.Ignore

        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return BlockDecision.Ignore

        // Gate 1 — never block ourselves, the launcher, or the system.
        if (isExempt(pkg, allowedPackages)) return BlockDecision.Ignore

        // Gates 2–3 — known target in Block mode only.
        if (target == null || mode != AppMode.BLOCK) return BlockDecision.Ignore

        // Gate 4 — 500 ms per-package dedupe (splash -> main churn).
        val last = lastLaunchAt[pkg]
        if (last != null && nowMillis - last in 0 until dedupeWindowMillis) return BlockDecision.Ignore
        lastLaunchAt[pkg] = nowMillis

        // Gate 5 — schedule reason wins over the app-block reason.
        return BlockDecision.Launch(
            platform = platformSlug(target),
            reason = if (scheduleLocked) BlockReason.SCHEDULE else BlockReason.APP_BLOCK,
            packageName = pkg,
        )
    }

    fun isExempt(packageName: String, allowedPackages: Set<String> = emptySet()): Boolean =
        packageName == ownPackage ||
            packageName.startsWith(ANDROID_PACKAGE_PREFIX) ||
            packageName == PERMISSION_CONTROLLER_PACKAGE ||
            allowedPackages.contains(packageName)

    /** A restarted service starts with an empty debounce map, as in the original. */
    fun reset() {
        lastLaunchAt.clear()
    }

    companion object {
        const val DEFAULT_OWN_PACKAGE = "dev.k3vinb5.freesocial"
        const val DEFAULT_DEDUPE_MILLIS = 500L
        const val ANDROID_PACKAGE_PREFIX = "com.android."
        const val PERMISSION_CONTROLLER_PACKAGE = "com.google.android.permissioncontroller"

        /** TikTok's global build; SettingsRepository keys its mode under [AppTarget.TIKTOK]. */
        const val PACKAGE_TIKTOK_GLOBAL = "com.ss.android.ugc.trill"

        fun resolveTarget(packageName: String?): AppTarget? {
            val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (pkg.equals(PACKAGE_TIKTOK_GLOBAL, ignoreCase = true)) return AppTarget.TIKTOK
            return AppTarget.entries.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }
        }

        fun platformSlug(target: AppTarget): String = when (target) {
            AppTarget.INSTAGRAM -> "instagram"
            AppTarget.YOUTUBE -> "youtube"
            AppTarget.TIKTOK -> "tiktok"
        }
    }
}
