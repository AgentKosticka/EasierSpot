package com.agentkosticka.easierspot.shared

enum class SystemWifiPickerState {
    NATIVE_REMOTE_ENTRIES,
    SUGGESTION_ACTIVE,
    SUGGESTION_READY,
    SUGGESTION_NEEDS_REFRESH,
    SUGGESTION_APPROVAL_PENDING,
    SUGGESTION_APPROVAL_REJECTED
}

internal fun resolveSystemWifiPickerState(
    nativeRemoteEntriesActive: Boolean,
    suggestionApprovalPending: Boolean,
    suggestionApprovalRejected: Boolean,
    trustedNetworkCount: Int,
    pickerSelectableSuggestionCount: Int
): SystemWifiPickerState = when {
    nativeRemoteEntriesActive -> SystemWifiPickerState.NATIVE_REMOTE_ENTRIES
    suggestionApprovalRejected -> SystemWifiPickerState.SUGGESTION_APPROVAL_REJECTED
    suggestionApprovalPending -> SystemWifiPickerState.SUGGESTION_APPROVAL_PENDING
    pickerSelectableSuggestionCount > 0 -> SystemWifiPickerState.SUGGESTION_ACTIVE
    trustedNetworkCount > 0 -> SystemWifiPickerState.SUGGESTION_NEEDS_REFRESH
    else -> SystemWifiPickerState.SUGGESTION_READY
}
