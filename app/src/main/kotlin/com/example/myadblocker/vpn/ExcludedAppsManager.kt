package com.example.myadblocker.vpn

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class InstalledAppInfo(
    val packageName: String,
    val label: String
)

object ExcludedAppsManager {
    private const val PREFS_NAME = "adblocker_prefs"
    private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"

    @Volatile
    private var initialized = false

    private val _excludedPackages = MutableStateFlow<Set<String>>(emptySet())
    val excludedPackages: StateFlow<Set<String>> = _excludedPackages.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getStringSet(KEY_EXCLUDED_PACKAGES, emptySet()) ?: emptySet()
            _excludedPackages.value = HashSet(saved)
            initialized = true
        }
    }

    fun getExcludedPackages(): Set<String> = _excludedPackages.value

    fun toggleExcluded(context: Context, packageName: String) {
        init(context)
        val appContext = context.applicationContext
        synchronized(this) {
            val next = HashSet(_excludedPackages.value)
            if (next.contains(packageName)) {
                next.remove(packageName)
            } else {
                next.add(packageName)
            }
            _excludedPackages.value = next
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_EXCLUDED_PACKAGES, HashSet(next))
                .apply()
        }
    }

    fun loadInstalledApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = pm.queryIntentActivities(intent, 0)
        val selfPkg = context.packageName

        return resolved
            .asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != selfPkg }
            .mapNotNull { pkg ->
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    InstalledAppInfo(packageName = pkg, label = label)
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
