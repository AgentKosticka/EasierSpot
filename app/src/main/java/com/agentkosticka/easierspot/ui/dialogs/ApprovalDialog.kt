package com.agentkosticka.easierspot.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.agentkosticka.easierspot.R

class ApprovalDialog : DialogFragment() {
    companion object {
        private const val ARG_DEVICE_ID = "device_id"
        private const val ARG_DEVICE_NAME = "device_name"
        private const val ARG_DEVICE_ADDRESS = "device_address"
        private const val ARG_IS_NEW_DEVICE = "is_new_device"
        private const val ARG_NICKNAME = "nickname"
        private val CLIENT_PREFIX_REGEX = Regex("(?i)^client[-_\\s]*")

        fun newInstance(
            deviceId: String,
            deviceName: String?,
            deviceAddress: String,
            isNewDevice: Boolean,
            nickname: String?
        ): ApprovalDialog {
            val dialog = ApprovalDialog()
            val args = Bundle()
            args.putString(ARG_DEVICE_ID, deviceId)
            args.putString(ARG_DEVICE_NAME, deviceName)
            args.putString(ARG_DEVICE_ADDRESS, deviceAddress)
            args.putBoolean(ARG_IS_NEW_DEVICE, isNewDevice)
            args.putString(ARG_NICKNAME, nickname)
            dialog.arguments = args
            return dialog
        }
    }

    interface ApprovalListener {
        fun onApprove(deviceId: String, deviceName: String?, deviceAddress: String)
        fun onDeny(deviceAddress: String)
    }

    private var listener: ApprovalListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? ApprovalListener
            ?: throw IllegalStateException("Host activity must implement ApprovalListener")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val deviceId = arguments?.getString(ARG_DEVICE_ID).orEmpty()
        val deviceName = arguments?.getString(ARG_DEVICE_NAME)
        val deviceAddress = arguments?.getString(ARG_DEVICE_ADDRESS) ?: ""
        val isNewDevice = arguments?.getBoolean(ARG_IS_NEW_DEVICE, true) ?: true
        val nickname = arguments?.getString(ARG_NICKNAME)?.trim()?.takeIf { it.isNotEmpty() }

        val normalizedDeviceId = normalizeIdentityForDisplay(deviceId)
        val normalizedDeviceName = normalizeIdentityForDisplay(deviceName)
            .takeUnless { it == getString(R.string.approval_identity_unknown) }
        val joinTarget = nickname ?: normalizedDeviceName ?: normalizedDeviceId
        val primaryIdentity = normalizedDeviceId
        val secondaryIdentity: String? = null

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_approval, null)

        val titleTextView = view.findViewById<TextView>(R.id.tv_approval_title)
        val primaryIdentityTextView = view.findViewById<TextView>(R.id.tv_primary_identity)
        val secondaryIdentityTextView = view.findViewById<TextView>(R.id.tv_secondary_identity)

        titleTextView.text = if (isNewDevice) {
            getString(R.string.approval_title_new_device)
        } else {
            getString(R.string.approval_title_existing_device, joinTarget)
        }
        primaryIdentityTextView.text = getString(R.string.approval_identity_id_only, primaryIdentity)
        if (secondaryIdentity != null) {
            secondaryIdentityTextView.visibility = View.VISIBLE
            secondaryIdentityTextView.text = secondaryIdentity
        } else {
            secondaryIdentityTextView.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()

        view.findViewById<Button>(R.id.btn_approve).setOnClickListener {
            listener?.onApprove(deviceId, deviceName, deviceAddress)
            dismiss()
        }

        view.findViewById<Button>(R.id.btn_deny).setOnClickListener {
            listener?.onDeny(deviceAddress)
            dismiss()
        }

        return dialog
    }

    private fun normalizeIdentityForDisplay(rawIdentity: String?): String {
        val trimmed = rawIdentity?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return getString(R.string.approval_identity_unknown)
        }

        var normalized = trimmed
        while (true) {
            val next = CLIENT_PREFIX_REGEX.replace(normalized, "")
                .trimStart('-', '_', ' ')
                .trim()
            if (next == normalized) break
            normalized = next
        }

        return normalized.ifBlank { trimmed }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}
