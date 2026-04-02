package com.agentkosticka.easierspot.ui.permissions

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ui.MainActivity
import com.agentkosticka.easierspot.ui.server.ShizukuHelper
import com.agentkosticka.easierspot.util.LogUtils

class PermissionsActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "PermissionsActivity"
        const val EXTRA_VIEW_ONLY = "extra_view_only"
    }

    private data class PermissionGroupUi(
        val permissions: List<String>,
        val button: Button
    )

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshUi()
        }

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshUi()
        }

    private lateinit var permissionUiItems: List<PermissionGroupUi>
    private lateinit var continueButton: Button
    private lateinit var shizukuButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewOnly = intent.getBooleanExtra(EXTRA_VIEW_ONLY, false)
        if (!viewOnly && AppPermissions.missingRuntimePermissions(this).isEmpty()) {
            openMainAndFinish()
            return
        }
        setContentView(R.layout.activity_permissions)

        title = getString(R.string.permissions_title)

        permissionUiItems = buildPermissionUiItems()
        continueButton = findViewById(R.id.btn_permissions_continue)
        shizukuButton = findViewById(R.id.btn_grant_shizuku)

        findViewById<Button>(R.id.btn_grant_all_required).setOnClickListener {
            val missingRequired = AppPermissions.requiredRuntimePermissions
                .filterNot { AppPermissions.isGranted(this, it) }
            if (missingRequired.isNotEmpty()) {
                requestMultiplePermissionsLauncher.launch(missingRequired.toTypedArray())
            } else {
                refreshUi()
            }
        }

        continueButton.setOnClickListener {
            if (AppPermissions.hasRequiredRuntimePermissions(this)) {
                if (viewOnly) {
                    finish()
                } else {
                    openMainAndFinish()
                }
            }
        }

        shizukuButton.setOnClickListener {
            requestOptionalShizukuPermission()
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun buildPermissionUiItems(): List<PermissionGroupUi> {
        val items = mutableListOf(
            PermissionGroupUi(
                permissions = listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ),
                button = findViewById(R.id.btn_grant_bluetooth_group)
            ),
            PermissionGroupUi(
                permissions = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                button = findViewById(R.id.btn_grant_location_group)
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            items += PermissionGroupUi(
                permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                findViewById(R.id.btn_grant_notifications)
            )
        } else {
            findViewById<View>(R.id.row_notifications).visibility = View.GONE
        }

        items.forEach { item ->
            item.button.setOnClickListener {
                val missing = item.permissions.filterNot { permission ->
                    AppPermissions.isGranted(this, permission)
                }
                when {
                    missing.isEmpty() -> refreshUi()
                    missing.size == 1 -> requestPermissionLauncher.launch(missing.first())
                    else -> requestMultiplePermissionsLauncher.launch(missing.toTypedArray())
                }
            }
        }

        return items
    }

    private fun refreshUi() {
        permissionUiItems.forEach { item ->
            val granted = item.permissions.all { permission ->
                AppPermissions.isGranted(this, permission)
            }
            item.button.isEnabled = !granted
            item.button.text = if (granted) {
                getString(R.string.permission_button_granted)
            } else {
                getString(R.string.permission_button_grant)
            }
        }

        val requiredGranted = AppPermissions.hasRequiredRuntimePermissions(this)
        continueButton.isEnabled = requiredGranted
        continueButton.text = if (requiredGranted) {
            getString(R.string.permissions_continue)
        } else {
            getString(R.string.permissions_continue_locked)
        }

        val missingRequired = AppPermissions.requiredRuntimePermissions.filterNot { AppPermissions.isGranted(this, it) }
        if (missingRequired.isNotEmpty()) {
            LogUtils.w(TAG, "Missing required runtime permissions: $missingRequired")
        }

        val shizukuGranted = ShizukuHelper.hasShizukuPermission()
        shizukuButton.isEnabled = !shizukuGranted
        shizukuButton.text = if (shizukuGranted) {
            getString(R.string.permission_button_granted)
        } else {
            getString(R.string.permission_button_grant)
        }
    }

    private fun openMainAndFinish() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun requestOptionalShizukuPermission() {
        ShizukuHelper.requestShizukuPermission(
            activity = this,
            onGranted = { refreshUi() },
            onDenied = { refreshUi() }
        )
    }
}
