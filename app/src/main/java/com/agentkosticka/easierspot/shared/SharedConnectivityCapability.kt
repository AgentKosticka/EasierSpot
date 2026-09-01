package com.agentkosticka.easierspot.shared

sealed interface SharedConnectivityCapability {
    data object ApiUnavailable : SharedConnectivityCapability
    data object ProviderAlreadyConfigured : SharedConnectivityCapability
    data object ProviderActivated : SharedConnectivityCapability
    data class Blocked(val reason: String) : SharedConnectivityCapability
}

internal val SharedConnectivityCapability.isActive: Boolean
    get() = this is SharedConnectivityCapability.ProviderAlreadyConfigured ||
        this is SharedConnectivityCapability.ProviderActivated
