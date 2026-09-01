package com.agentkosticka.easierspot.shared

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
