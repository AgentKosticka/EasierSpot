package com.agentkosticka.easierspot.shared

import java.util.Locale

internal fun isLikelyWifiPickerWindow(packageName: String?, className: String?): Boolean {
    val pkg = packageName.orEmpty().lowercase(Locale.ROOT)
    if (pkg != "com.android.settings" && !pkg.endsWith(".settings")) return false

    val name = className.orEmpty().lowercase(Locale.ROOT)
    if (name.isBlank()) return false
    val excluded = listOf(
        "details",
        "savedaccess",
        "configurewifi",
        "wifiinfo",
        "wifip2p",
        "hotspot",
        "tether",
        "addnetwork",
        "networkrequest"
    )
    if (excluded.any(name::contains)) return false

    return name.contains("wifipicker") ||
        name.contains("wifisettings") ||
        name.contains("networkprovidersettings") ||
        name.contains("networkselect") ||
        name.contains("internetdashboard") ||
        (name.contains("wifi") &&
            (name.contains("settings") || name.contains("picker") || name.endsWith("activity")))
}

internal fun mergeEnabledAccessibilityServices(current: String?, component: String): String {
    val existing = current.orEmpty()
        .trim()
        .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
        ?.split(':')
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        .orEmpty()
        .toMutableList()

    val canonicalTarget = canonicalAccessibilityComponent(component)
    if (existing.none { canonicalAccessibilityComponent(it) == canonicalTarget }) {
        existing += component
    }
    return existing.distinct().joinToString(":")
}

internal fun enabledAccessibilitySettingContains(current: String?, component: String): Boolean {
    val target = canonicalAccessibilityComponent(component)
    return current.orEmpty().split(':').any { canonicalAccessibilityComponent(it.trim()) == target }
}

private fun canonicalAccessibilityComponent(value: String): String {
    val slash = value.indexOf('/')
    if (slash <= 0 || slash == value.lastIndex) return value
    val pkg = value.substring(0, slash)
    val rawClass = value.substring(slash + 1)
    val className = if (rawClass.startsWith('.')) pkg + rawClass else rawClass
    return "$pkg/$className"
}
