package com.agentkosticka.easierspot

import android.content.ComponentName
import android.content.pm.PackageManager
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agentkosticka.easierspot.data.db.AppDatabase
import com.agentkosticka.easierspot.service.ClientServiceBootReceiver
import com.agentkosticka.easierspot.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EcosystemInfrastructureInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bootAndScanReceiverIsPrivateAndEnabled() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, ClientServiceBootReceiver::class.java), 0
        )
        assertTrue(info.enabled)
        assertFalse(info.exported)
    }

    @Test
    fun launcherOpensDashboardInsteadOfPermissionRedirect() {
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull(launcher)
        assertTrue(launcher?.component == ComponentName(context, MainActivity::class.java))
    }

    @Test
    fun backupsAreDisabledForDeviceBoundPairings() {
        @Suppress("DEPRECATION")
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertFalse(appInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }

    @Test
    fun clientDeclaresAndRequiresFineLocationForWifiIdentity() {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        assertTrue(
            packageInfo.requestedPermissions.orEmpty().contains(
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
        assertTrue(
            com.agentkosticka.easierspot.ui.permissions.AppPermissions
                .requiredFor(com.agentkosticka.easierspot.ui.permissions.AppPermissions.Role.CLIENT)
                .contains(android.Manifest.permission.ACCESS_FINE_LOCATION)
        )
        assertFalse(
            com.agentkosticka.easierspot.ui.permissions.AppPermissions
                .requiredFor(com.agentkosticka.easierspot.ui.permissions.AppPermissions.Role.SERVER)
                .contains(android.Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    @Test
    fun trustedServerSchemaContainsNoPasswordColumn() = runBlocking(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context).openHelper.writableDatabase
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(trusted_servers)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        assertTrue(
            columns.containsAll(
                setOf(
                    "fingerprint",
                    "discoveryToken",
                    "advertisedRevision",
                    "provisionedRevision",
                    "wakeCounter",
                    "controlCounter",
                    "suggestionLatencyMs",
                    "shizukuLatencyMs"
                )
            )
        )
        assertFalse(columns.any { it.contains("password", ignoreCase = true) })
    }

    @Test
    fun unifiedDashboardContainsSharingConnectionAndHealthControls() {
        val themed = ContextThemeWrapper(context, R.style.Theme_EasierSpot)
        val view = LayoutInflater.from(themed).inflate(R.layout.activity_main, null)
        assertNotNull(view.findViewById<android.view.View>(R.id.btn_server_mode))
        assertNotNull(view.findViewById<android.view.View>(R.id.btn_client_mode))
        assertNotNull(view.findViewById<android.view.View>(R.id.tv_setup_health))
        assertNotNull(view.findViewById<android.view.View>(R.id.btn_copy_report))
    }
}
