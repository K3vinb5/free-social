package dev.k3vinb5.freesocial.core

import android.content.Context

/**
 * The installed `versionName` of a package, or null when it is not installed. Narrow on purpose:
 * the staleness rules stay JVM-testable and only this lookup touches `PackageManager`.
 */
fun interface InstalledVersionSource {
    fun versionName(packageName: String): String?
}

/** The real source. Never throws — an unknown package reads as "not installed". */
class PackageManagerVersionSource(private val context: Context) : InstalledVersionSource {
    @Suppress("DEPRECATION")
    override fun versionName(packageName: String): String? = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()
}
