package dev.k3vinb5.freesocial.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.k3vinb5.freesocial.R
import dev.k3vinb5.freesocial.core.DaySchedule
import dev.k3vinb5.freesocial.core.ScheduleState
import dev.k3vinb5.freesocial.core.ShortVideoBudget
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Per-app filtering mode. Stored values are stable strings so prefs stay readable. */
enum class AppMode(val storageValue: String) {
    FILTER("filter"),
    BLOCK("block"),
    ALLOW("allow"),
    ;

    companion object {
        fun fromStorage(raw: String?): AppMode =
            entries.firstOrNull { it.storageValue == raw } ?: FILTER
    }
}

/** One per-app surface toggle: stable key, string resource for its label, default per DESIGN.md. */
data class SurfaceToggle(
    val key: String,
    val labelRes: Int,
    val default: Boolean,
)

/** The three target apps with their surface toggles and rules-verified versions (DESIGN.md F7–F9). */
enum class AppTarget(
    val packageName: String,
    val nameRes: Int,
    val infoRes: Int,
    val rulesVersion: String,
    val surfaces: List<SurfaceToggle>,
) {
    INSTAGRAM(
        packageName = "com.instagram.android",
        nameRes = R.string.app_instagram,
        infoRes = R.string.info_instagram,
        rulesVersion = "Instagram 454.0.0.54",
        surfaces = listOf(
            SurfaceToggle("hide_feed_reels", R.string.surface_hide_feed_reels, true),
            SurfaceToggle("hide_reels_tab", R.string.surface_hide_reels_tab, true),
        ),
    ),
    YOUTUBE(
        packageName = "com.google.android.youtube",
        nameRes = R.string.app_youtube,
        infoRes = R.string.info_youtube,
        rulesVersion = "YouTube 21.43",
        surfaces = listOf(
            SurfaceToggle("hide_shorts_shelf", R.string.surface_hide_shorts_shelf, true),
            SurfaceToggle("shorts_count_budget", R.string.surface_shorts_count_budget, true),
        ),
    ),
    TIKTOK(
        packageName = "com.zhiliaoapp.musically",
        nameRes = R.string.app_tiktok,
        infoRes = R.string.info_tiktok,
        rulesVersion = "TikTok 47.6",
        surfaces = listOf(
            SurfaceToggle("videos_count_budget", R.string.surface_videos_count_budget, true),
            SurfaceToggle("back_when_spent", R.string.surface_back_when_spent, true),
        ),
    ),
    ;

    fun surface(key: String): SurfaceToggle? = surfaces.firstOrNull { it.key == key }

    companion object {
        /**
         * Resolves a block intent's `platform` slug or `package_name` extra to a known target.
         * Returns null for anything we do not ship rules for, so the block screen can fall back
         * to a generic title.
         */
        fun fromPlatform(platform: String?, packageName: String?): AppTarget? {
            val byPackage = packageName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { pkg -> entries.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) } }
            if (byPackage != null) return byPackage
            return when (platform?.trim()?.lowercase()) {
                "instagram", "ig" -> INSTAGRAM
                "youtube", "yt" -> YOUTUBE
                "tiktok", "tt" -> TIKTOK
                else -> null
            }
        }
    }
}

/** Storage seam so the repository is testable without Android's SharedPreferences. */
interface PrefsStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getString(key: String): String?
    fun putString(key: String, value: String)

    /**
     * Calls [onChange] after any write to this store (same process only — a `run-as` or another
     * process' write is never observed). Returns a handle that unregisters the callback.
     */
    fun observe(onChange: () -> Unit): AutoCloseable = AutoCloseable {}
}

private class SharedPrefsStore(private val prefs: SharedPreferences) : PrefsStore {
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()

    // SharedPreferences keeps listeners weakly, so the caller must hold the returned handle.
    override fun observe(onChange: () -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

/** The nightly lock window as wall-clock minutes from midnight ("22:00–07:30"). */
data class LockWindow(val startMinutes: Int, val endMinutes: Int)

/**
 * Live permission snapshot shared by the first-run wizard (F5) and the permissions screen (F12).
 *
 * The block service is optional, so [grantedRequired] counts only the three permissions the
 * filtering experience cannot work without.
 */
data class PermissionStatus(
    val filterService: Boolean = false,
    val blockService: Boolean = false,
    val overlay: Boolean = false,
    val batteryExempt: Boolean = false,
) {
    val grantedRequired: Int get() = listOf(filterService, overlay, batteryExempt).count { it }
    val allRequiredGranted: Boolean get() = filterService && overlay && batteryExempt

    /** All four switches shown on F12, including the optional block service ("3 of 4"). */
    val grantedCount: Int get() = listOf(filterService, blockService, overlay, batteryExempt).count { it }

    companion object {
        const val TOTAL = 4
    }
}

/**
 * All user-facing settings, persisted in the `"settings"` SharedPreferences file and exposed as
 * Compose-observable state. Deliberately boring: plain `mutableStateOf` fields, writes go straight
 * through to storage, no coroutines.
 */
class SettingsRepository(private val store: PrefsStore) {

    var filteringEnabled by mutableStateOf(store.getBoolean(KEY_FILTERING, true))
        private set

    var budgetLimitMinutes by mutableStateOf(
        store.getInt(KEY_BUDGET_LIMIT, ShortVideoBudget.DEFAULT_LIMIT_MINUTES).coerceIn(MIN_BUDGET, MAX_BUDGET),
    )
        private set

    var budgetUsedSeconds by mutableStateOf(store.getLong(KEY_BUDGET_USED, 0L))
        private set

    var budgetDate by mutableStateOf(store.getString(KEY_BUDGET_DATE))
        private set

    /** False until the F1–F5 wizard's "Finish setup" runs; gates the first launch (SPEC §2.4). */
    var firstRunCompleted by mutableStateOf(store.getBoolean(KEY_FIRST_RUN, false))
        private set

    /**
     * Last observed permission state. Screens refresh it on resume via [refreshPermissions];
     * keeping it here lets the wizard and the permissions page agree without re-reading twice.
     */
    var permissionStatus by mutableStateOf(PermissionStatus())
        private set

    var scheduleEnabled by mutableStateOf(store.getBoolean(KEY_SCHEDULE_ENABLED, true))
        private set

    var windDownMinutes by mutableStateOf(
        store.getInt(KEY_WIND_DOWN, ScheduleState.DEFAULT_WIND_DOWN_MINUTES).coerceIn(0, MAX_MINUTES),
    )
        private set

    var morningLockMinutes by mutableStateOf(
        store.getInt(KEY_MORNING_LOCK, ScheduleState.DEFAULT_MORNING_LOCK_MINUTES).coerceIn(0, MAX_MINUTES),
    )
        private set

    private val modes = mutableStateMapOf<AppTarget, AppMode>()
    private val surfaceStates = mutableStateMapOf<String, Boolean>()
    private val days = mutableStateMapOf<Int, DaySchedule>()

    /**
     * D10/D11: every consumer of this repository (UI, `FilterService`, `BlockService`, the schedule
     * alarm) owns its own instance, so a write anywhere must refresh everywhere. A same-process
     * preference change re-reads [reload]; external `run-as` writes are not observable this way.
     * The handle is held so SharedPreferences' weak listener registry cannot drop the callback.
     * [writeDepth] keeps a multi-key write (see [recordBudgetSeconds]) from being observed halfway.
     */
    private var writeDepth = 0

    @Suppress("unused")
    private val storeObserver: AutoCloseable = store.observe { if (writeDepth == 0) reload() }

    init {
        reload()
    }

    private inline fun batch(block: () -> Unit) {
        writeDepth++
        try {
            block()
        } finally {
            writeDepth--
        }
    }

    /** Re-reads every persisted field into the observable state. Never writes back to the store. */
    fun reload() {
        filteringEnabled = store.getBoolean(KEY_FILTERING, true)
        budgetLimitMinutes = store.getInt(KEY_BUDGET_LIMIT, ShortVideoBudget.DEFAULT_LIMIT_MINUTES)
            .coerceIn(MIN_BUDGET, MAX_BUDGET)
        budgetUsedSeconds = store.getLong(KEY_BUDGET_USED, 0L)
        budgetDate = store.getString(KEY_BUDGET_DATE)
        firstRunCompleted = store.getBoolean(KEY_FIRST_RUN, false)
        scheduleEnabled = store.getBoolean(KEY_SCHEDULE_ENABLED, true)
        windDownMinutes = store.getInt(KEY_WIND_DOWN, ScheduleState.DEFAULT_WIND_DOWN_MINUTES)
            .coerceIn(0, MAX_MINUTES)
        morningLockMinutes = store.getInt(KEY_MORNING_LOCK, ScheduleState.DEFAULT_MORNING_LOCK_MINUTES)
            .coerceIn(0, MAX_MINUTES)
        AppTarget.entries.forEach { app ->
            modes[app] = AppMode.fromStorage(store.getString(modeKey(app)))
            app.surfaces.forEach { surface ->
                surfaceStates[surfaceKey(app, surface.key)] =
                    store.getBoolean(surfaceKey(app, surface.key), surface.default)
            }
        }
        for (weekday in 1..7) {
            days[weekday] = DaySchedule(
                bedtimeMinutes = store.getInt(bedtimeKey(weekday), ScheduleState.DEFAULT_BEDTIME_MINUTES)
                    .coerceIn(0, 24 * 60 - 1),
                wakeMinutes = store.getInt(wakeKey(weekday), ScheduleState.DEFAULT_WAKE_MINUTES)
                    .coerceIn(0, 24 * 60 - 1),
            )
        }
    }

    // --- First run + permissions ---------------------------------------------------------

    fun completeFirstRun() {
        firstRunCompleted = true
        store.putBoolean(KEY_FIRST_RUN, true)
    }

    /** Reads the live permission state into [permissionStatus]; call from onResume. */
    fun refreshPermissions(context: Context): PermissionStatus {
        val status = readPermissionStatus(context)
        permissionStatus = status
        return status
    }

    // --- Master switch -------------------------------------------------------------------

    fun updateFiltering(enabled: Boolean) {
        filteringEnabled = enabled
        store.putBoolean(KEY_FILTERING, enabled)
    }

    // --- Per-app mode --------------------------------------------------------------------

    fun modeFor(app: AppTarget): AppMode = modes[app] ?: AppMode.FILTER

    fun setMode(app: AppTarget, mode: AppMode) {
        modes[app] = mode
        store.putString(modeKey(app), mode.storageValue)
    }

    // --- Per-app surfaces ----------------------------------------------------------------

    fun surfaceEnabled(app: AppTarget, key: String): Boolean =
        surfaceStates[surfaceKey(app, key)] ?: app.surface(key)?.default ?: true

    fun setSurfaceEnabled(app: AppTarget, key: String, enabled: Boolean) {
        if (app.surface(key) == null) return
        surfaceStates[surfaceKey(app, key)] = enabled
        store.putBoolean(surfaceKey(app, key), enabled)
    }

    // --- Short-video budget --------------------------------------------------------------

    /** Seconds counted today; a stored counter from an earlier date reads as zero. */
    fun usedSecondsToday(now: ZonedDateTime): Long =
        if (budgetDate == now.toLocalDate().toString()) budgetUsedSeconds else 0L

    /** Adds short-video seconds, rolling the counter over at local midnight. */
    fun recordBudgetSeconds(seconds: Long, now: ZonedDateTime) {
        if (seconds <= 0) return
        val today = now.toLocalDate().toString()
        val base = if (budgetDate == today) budgetUsedSeconds else 0L
        val next = base + seconds
        // Two keys, one logical value: pause the observer so no reload reads the half-written
        // pair, and write the counter before the date so an intermediate reader cannot attribute
        // today's seconds to yesterday.
        batch {
            store.putLong(KEY_BUDGET_USED, next)
            store.putString(KEY_BUDGET_DATE, today)
        }
        budgetUsedSeconds = next
        budgetDate = today
    }

    /** Remaining allowance in whole minutes, using [ShortVideoBudget]'s rounding semantics. */
    fun remainingMinutesCeil(now: ZonedDateTime, limitMinutes: Int = budgetLimitMinutes): Int {
        val budget = ShortVideoBudget(limitMinutes)
        budget.record(usedSecondsToday(now), now)
        return budget.remainingMinutesCeil(now)
    }

    fun updateBudgetLimit(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_BUDGET, MAX_BUDGET)
        budgetLimitMinutes = clamped
        store.putInt(KEY_BUDGET_LIMIT, clamped)
    }

    // --- Schedule ------------------------------------------------------------------------

    fun updateScheduleEnabled(enabled: Boolean) {
        scheduleEnabled = enabled
        store.putBoolean(KEY_SCHEDULE_ENABLED, enabled)
    }

    fun daySchedule(weekday: Int): DaySchedule = days[weekday] ?: ScheduleState().daySchedule(weekday)

    fun setBedtimeMinutes(weekday: Int, minutes: Int) {
        val current = daySchedule(weekday)
        val updated = current.copy(bedtimeMinutes = minutes.coerceIn(0, 24 * 60 - 1))
        days[weekday] = updated
        store.putInt(bedtimeKey(weekday), updated.bedtimeMinutes)
    }

    fun setWakeMinutes(weekday: Int, minutes: Int) {
        val current = daySchedule(weekday)
        val updated = current.copy(wakeMinutes = minutes.coerceIn(0, 24 * 60 - 1))
        days[weekday] = updated
        store.putInt(wakeKey(weekday), updated.wakeMinutes)
    }

    fun updateWindDown(minutes: Int) {
        val clamped = minutes.coerceIn(0, MAX_MINUTES)
        windDownMinutes = clamped
        store.putInt(KEY_WIND_DOWN, clamped)
    }

    fun updateMorningLock(minutes: Int) {
        val clamped = minutes.coerceIn(0, MAX_MINUTES)
        morningLockMinutes = clamped
        store.putInt(KEY_MORNING_LOCK, clamped)
    }

    fun scheduleState(): ScheduleState = ScheduleState(
        days = (1..7).associateWith { daySchedule(it) },
        windDownMinutes = windDownMinutes,
        morningLockMinutes = morningLockMinutes,
    )

    /** True only while the master "Nightly lock" switch is on and a window covers [now]. */
    fun isLocked(now: ZonedDateTime): Boolean = scheduleEnabled && scheduleState().isLocked(now)

    /**
     * The window that covers [now] if one is active, otherwise the next one to start ("tonight");
     * null while the master switch is off, so every preview reads as an off state.
     */
    fun tonightLockWindow(now: ZonedDateTime): LockWindow? {
        if (!scheduleEnabled) return null
        val today = now.toLocalDate()
        val candidates = listOf(today.minusDays(1), today, today.plusDays(1)).map { day ->
            windowBounds(day, daySchedule(day.dayOfWeek.value), now.zone)
        }
        val active = candidates.firstOrNull { !now.isBefore(it.first) && now.isBefore(it.second) }
        if (active != null && scheduleState().isLocked(now)) {
            return LockWindow(clockOf(active.first), clockOf(active.second))
        }
        val upcoming = candidates.filter { it.first.isAfter(now) }.minByOrNull { it.first } ?: candidates.last()
        return LockWindow(clockOf(upcoming.first), clockOf(upcoming.second))
    }

    private fun windowBounds(
        lockDay: LocalDate,
        schedule: DaySchedule,
        zone: ZoneId,
    ): Pair<ZonedDateTime, ZonedDateTime> {
        val start = lockDay.atStartOfDay()
            .plusMinutes((schedule.bedtimeMinutes - windDownMinutes).toLong())
            .atZone(zone)
        val endDay = if (schedule.wakeMinutes > schedule.bedtimeMinutes) lockDay else lockDay.plusDays(1)
        val end = endDay.atStartOfDay()
            .plusMinutes((schedule.wakeMinutes + morningLockMinutes).toLong())
            .atZone(zone)
        return start to end
    }

    companion object {
        const val PREFS_NAME = "settings"
        const val MIN_BUDGET = 0
        const val MAX_BUDGET = 240
        const val MAX_MINUTES = 180

        const val KEY_FILTERING = "filtering_enabled"
        const val KEY_FIRST_RUN = "first_run_completed"
        const val KEY_BUDGET_LIMIT = "budget_limit_minutes"
        const val KEY_BUDGET_USED = "budget_used_seconds"
        const val KEY_BUDGET_DATE = "budget_date"
        const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        const val KEY_WIND_DOWN = "wind_down_minutes"
        const val KEY_MORNING_LOCK = "morning_lock_minutes"

        /** Accessibility component class names the services are (or will be) declared with. */
        const val FILTER_SERVICE_CLASS = "dev.k3vinb5.freesocial.filter.FilterService"
        const val BLOCK_SERVICE_CLASS = "dev.k3vinb5.freesocial.block.BlockService"

        /**
         * True when [enabledServices] — the raw `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
         * string, colon-separated `package/class` pairs — contains one of [packageName]'s services.
         * With [componentClass] set the match is exact; otherwise any service of the package counts.
         */
        fun accessibilityServiceEnabled(
            enabledServices: String?,
            packageName: String,
            componentClass: String? = null,
        ): Boolean {
            if (enabledServices.isNullOrBlank()) return false
            return enabledServices.split(':').any { entry ->
                val flattened = entry.trim()
                if (flattened.isEmpty()) {
                    false
                } else if (componentClass != null) {
                    flattened.equals("$packageName/$componentClass", ignoreCase = true)
                } else {
                    flattened.startsWith("$packageName/", ignoreCase = true)
                }
            }
        }

        /**
         * Live permission state straight from the system. Never cached by Android, so call it
         * from onResume: the user grants permissions in other activities.
         */
        fun readPermissionStatus(context: Context): PermissionStatus {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return PermissionStatus(
                filterService = accessibilityServiceEnabled(
                    enabledServices,
                    context.packageName,
                    FILTER_SERVICE_CLASS,
                ),
                blockService = accessibilityServiceEnabled(
                    enabledServices,
                    context.packageName,
                    BLOCK_SERVICE_CLASS,
                ),
                overlay = Settings.canDrawOverlays(context),
                batteryExempt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true,
            )
        }

        fun from(context: Context): SettingsRepository =
            SettingsRepository(SharedPrefsStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)))

        fun modeKey(app: AppTarget): String = "app_mode_${app.packageName}"
        fun surfaceKey(app: AppTarget, key: String): String = "surface_${app.packageName}_$key"
        fun bedtimeKey(weekday: Int): String = "schedule_day_${weekday}_bedtime"
        fun wakeKey(weekday: Int): String = "schedule_day_${weekday}_wake"

        /** "22:30" — 24-hour wall clock from minutes past midnight. */
        fun formatClock(minutes: Int): String {
            val normalized = minutes.mod(24 * 60)
            return "%02d:%02d".format(normalized / 60, normalized % 60)
        }

        private fun clockOf(instant: ZonedDateTime): Int = instant.hour * 60 + instant.minute
    }
}
