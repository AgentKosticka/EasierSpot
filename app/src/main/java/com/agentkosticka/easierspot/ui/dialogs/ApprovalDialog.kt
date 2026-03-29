package com.agentkosticka.easierspot.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
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

        fun newInstance(deviceId: String, deviceName: String?, deviceAddress: String): ApprovalDialog {
            val dialog = ApprovalDialog()
            val args = Bundle()
            args.putString(ARG_DEVICE_ID, deviceId)
            args.putString(ARG_DEVICE_NAME, deviceName)
            args.putString(ARG_DEVICE_ADDRESS, deviceAddress)
            dialog.arguments = args
            return dialog
        }
    }

    interface ApprovalListener {
        fun onApprove(deviceId: String, deviceAddress: String)
        fun onDeny(deviceAddress: String)
    }

    private var listener: ApprovalListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? ApprovalListener
            ?: throw IllegalStateException("Host activity must implement ApprovalListener")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val deviceId = arguments?.getString(ARG_DEVICE_ID) ?: "Unknown"
        val deviceName = arguments?.getString(ARG_DEVICE_NAME) ?: "Unknown Device"
        val deviceAddress = arguments?.getString(ARG_DEVICE_ADDRESS) ?: ""

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_approval, null)

        view.findViewById<TextView>(R.id.tv_device_id).text = "Device ID: $deviceId"
        view.findViewById<TextView>(R.id.tv_device_name).text = "Device Name: $deviceName"

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()

        view.findViewById<Button>(R.id.btn_approve).setOnClickListener {
            listener?.onApprove(deviceId, deviceAddress)
            dismiss()
        }

        view.findViewById<Button>(R.id.btn_deny).setOnClickListener {
            listener?.onDeny(deviceAddress)
            dismiss()
        }

        return dialog
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}
