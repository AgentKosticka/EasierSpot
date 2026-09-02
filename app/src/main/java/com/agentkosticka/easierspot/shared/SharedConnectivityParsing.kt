package com.agentkosticka.easierspot.shared

internal data class FabricatedSharedConnectivityValue(
    val name: String,
    val resourceName: String,
    val type: String,
    val value: String
)

internal fun sharedConnectivityFabricatedValues(
    packageName: String,
    serviceAction: String
): List<FabricatedSharedConnectivityValue> = listOf(
    FabricatedSharedConnectivityValue(
        name = "EasierSpotProviderPackage",
        resourceName = "android:string/config_sharedConnectivityServicePackage",
        type = "string",
        value = packageName
    ),
    FabricatedSharedConnectivityValue(
        name = "EasierSpotProviderAction",
        resourceName = "android:string/config_sharedConnectivityServiceIntentAction",
        type = "string",
        value = serviceAction
    ),
    FabricatedSharedConnectivityValue(
        name = "EasierSpotHotspotNetworks",
        resourceName = "android:bool/config_hotspotNetworksEnabledForService",
        type = "0x12",
        value = "1"
    )
)

internal fun parseOverlayLookupValue(output: String): String? {
    val lines = output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    if (lines.isEmpty()) return null
    if (lines.any { line ->
            line.contains("error", ignoreCase = true) ||
                line.contains("not found", ignoreCase = true) ||
                line.contains("no entry", ignoreCase = true)
        }) return null
    val raw = lines.last()
        .substringAfterLast("->")
        .substringAfterLast(" = ")
        .trim()
        .removeSurrounding("\"")
    return raw.takeIf(String::isNotBlank)
}

internal fun parseOverlayLookupBoolean(output: String): Boolean? =
    when (parseOverlayLookupValue(output)?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

internal fun parseDeviceConfigBoolean(output: String): Boolean? =
    when (output.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

internal fun parsePrivilegedUid(output: String): Int? = output.trim().toIntOrNull()
