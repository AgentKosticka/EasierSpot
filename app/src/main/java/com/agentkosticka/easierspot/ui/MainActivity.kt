package com.agentkosticka.easierspot.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.update.UpdateCheckCoordinator
import com.agentkosticka.easierspot.update.UpdateChecker
import com.agentkosticka.easierspot.ui.client.ClientActivity
import com.agentkosticka.easierspot.ui.settings.SettingsActivity
import com.agentkosticka.easierspot.ui.server.ServerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        
        // These are REQUIRED - app won't function without them
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        
        // These are OPTIONAL - nice to have but app works without them
        private val OPTIONAL_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,  // Only needed for server mode
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateText: TextView
    private val updateStateListener: (UpdateChecker.State) -> Unit = { state ->
        runOnUiThread { renderUpdateBanner(state) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        updateBanner = findViewById(R.id.update_warning_banner)
        updateText = findViewById(R.id.tv_update_warning_text)

        checkAndRequestPermissions()

        val clientModeButton = findViewById<android.widget.Button>(R.id.btn_client_mode)
        val serverModeButton = findViewById<android.widget.Button>(R.id.btn_server_mode)
        val settingsButton = findViewById<android.widget.ImageButton>(R.id.btn_settings)

        clientModeButton.setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }

        serverModeButton.setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateBanner.setOnClickListener { openLatestReleasePage() }
        renderUpdateBanner(UpdateChecker.getState(this))
        UpdateCheckCoordinator.addListener(updateStateListener)
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateState()
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateCheckCoordinator.removeListener(updateStateListener)
        mainScope.cancel()
    }

    private fun refreshUpdateState() {
        mainScope.launch {
            val state = withContext(Dispatchers.IO) {
                UpdateChecker.refreshIfStale(applicationContext)
            }
            renderUpdateBanner(state)
        }
    }

    private fun renderUpdateBanner(state: UpdateChecker.State) {
        if (state.updateAvailable) {
            val latest = state.latestVersionName
            updateText.text = if (!latest.isNullOrBlank()) {
                getString(R.string.update_available_with_version, latest)
            } else {
                getString(R.string.update_available_default)
            }
            updateBanner.visibility = View.VISIBLE
        } else {
            updateBanner.visibility = View.GONE
        }
    }

    private fun openLatestReleasePage() {
        val intent = Intent(Intent.ACTION_VIEW, UpdateChecker.LATEST_RELEASE_URL.toUri())
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.update_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val allPermissions = mutableListOf<String>()
        allPermissions.addAll(REQUIRED_PERMISSIONS)
        allPermissions.addAll(OPTIONAL_PERMISSIONS)
        
        // Add POST_NOTIFICATIONS for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            allPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Filter to only permissions not yet granted
        val permissionsToRequest = allPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        Log.d(TAG, "Permissions to request: ${permissionsToRequest.size}")
        permissionsToRequest.forEach { Log.d(TAG, "  - $it") }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            Log.d(TAG, "All permissions already granted")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != PERMISSION_REQUEST_CODE) return
        
        // Log all results for debugging
        Log.d(TAG, "Permission results:")
        permissions.forEachIndexed { index, perm ->
            val status = if (grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
            Log.d(TAG, "  $perm: $status")
        }

        // Check if any REQUIRED permission was denied
        val missingRequired = REQUIRED_PERMISSIONS.filter { required ->
            ContextCompat.checkSelfPermission(this, required) != PackageManager.PERMISSION_GRANTED
        }

        if (missingRequired.isNotEmpty()) {
            Log.w(TAG, "Missing required permissions: $missingRequired")
            val names = missingRequired.joinToString(", ") { it.substringAfterLast('.') }
            Toast.makeText(
                this,
                "Missing required permissions: $names",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Log.d(TAG, "All required permissions granted!")
            // Don't show any message - everything is fine
        }
    }
}
