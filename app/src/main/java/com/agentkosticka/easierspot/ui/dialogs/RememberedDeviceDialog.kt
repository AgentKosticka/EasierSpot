package com.agentkosticka.easierspot.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.data.model.RememberedServer

class RememberedDeviceDialog : DialogFragment() {
    interface RememberedDeviceListener {
        fun onRememberedDeviceSave(deviceId: String, nickname: String?, approvalPolicy: String)
        fun onRememberedDeviceForget(deviceId: String)
    }

    companion object {
        private const val ARG_DEVICE_ID = "device_id"
        private const val ARG_DEVICE_NAME = "device_name"
        private const val ARG_NICKNAME = "nickname"
        private const val ARG_APPROVAL_POLICY = "approval_policy"

        fun newInstance(
            deviceId: String,
            deviceName: String,
            currentNickname: String?,
            approvalPolicy: String
        ): RememberedDeviceDialog {
            return RememberedDeviceDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEVICE_ID, deviceId)
                    putString(ARG_DEVICE_NAME, deviceName)
                    putString(ARG_NICKNAME, currentNickname)
                    putString(ARG_APPROVAL_POLICY, approvalPolicy)
                }
            }
        }
    }

    private var listener: RememberedDeviceListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? RememberedDeviceListener
            ?: throw IllegalStateException("Host activity must implement RememberedDeviceListener")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val deviceId = arguments?.getString(ARG_DEVICE_ID).orEmpty()
        val deviceName = arguments?.getString(ARG_DEVICE_NAME).orEmpty()
        val currentNickname = arguments?.getString(ARG_NICKNAME)
        val currentPolicy = arguments?.getString(ARG_APPROVAL_POLICY) ?: RememberedServer.APPROVAL_POLICY_ASK

        val view = layoutInflater.inflate(R.layout.dialog_remembered_device, null)
        val titleView = view.findViewById<TextView>(R.id.tv_dialog_title)
        val nicknameInput = view.findViewById<EditText>(R.id.et_nickname)
        val approvalInput = view.findViewById<AutoCompleteTextView>(R.id.actv_approval_policy)

        titleView.text = getString(R.string.remembered_device_dialog_title, deviceName.ifBlank { deviceId })
        nicknameInput.setText(currentNickname.orEmpty())

        val options = listOf(
            PolicyOption(getString(R.string.approval_policy_approved), RememberedServer.APPROVAL_POLICY_APPROVED),
            PolicyOption(getString(R.string.approval_policy_ask), RememberedServer.APPROVAL_POLICY_ASK),
            PolicyOption(getString(R.string.approval_policy_denied), RememberedServer.APPROVAL_POLICY_DENIED)
        )
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, options.map { it.label })
        approvalInput.setAdapter(adapter)
        approvalInput.setOnClickListener { approvalInput.showDropDown() }

        val initialSelection = options.firstOrNull { it.value == currentPolicy } ?: options[1]
        approvalInput.setText(initialSelection.label, false)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(true)
            .create()

        view.findViewById<Button>(R.id.btn_save).setOnClickListener {
            val selectedPolicy = options.firstOrNull { it.label == approvalInput.text.toString() }?.value
                ?: RememberedServer.APPROVAL_POLICY_ASK
            listener?.onRememberedDeviceSave(deviceId, nicknameInput.text.toString(), selectedPolicy)
            dismiss()
        }

        view.findViewById<Button>(R.id.btn_forget).setOnClickListener {
            listener?.onRememberedDeviceForget(deviceId)
            dismiss()
        }

        view.findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            dismiss()
        }

        return dialog
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private data class PolicyOption(
        val label: String,
        val value: String
    )
}
