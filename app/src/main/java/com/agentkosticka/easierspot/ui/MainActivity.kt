package com.agentkosticka.easierspot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ui.client.ClientActivity
import com.agentkosticka.easierspot.ui.settings.SettingsActivity
import com.agentkosticka.easierspot.ui.server.ServerActivity

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
            android.widget.Toast.makeText(
                this,
                "Missing required permissions: $names",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            Log.d(TAG, "All required permissions granted!")
            // Don't show any message - everything is fine
        }
    }
}
