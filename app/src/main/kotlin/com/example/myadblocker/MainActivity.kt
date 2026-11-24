package com.example.myadblocker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.myadblocker.ui.main.MainScreen
import com.example.myadblocker.ui.theme.MyAdBlockerTheme
import com.example.myadblocker.vpn.AdBlockerState
import com.example.myadblocker.vpn.AdBlockerVpnService
import com.example.myadblocker.vpn.BlocklistManager
import com.example.myadblocker.vpn.ExcludedAppsManager

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            AdBlockerVpnService.start(this)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Foreground service works even if notification is denied, though notification won't show.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdBlockerState.init(this)
        BlocklistManager.init(this)
        ExcludedAppsManager.init(this)
        requestNotificationPermissionIfNeeded()

        if (intent?.action == ACTION_REQUEST_VPN) {
            requestAndStartVpn()
        }

        enableEdgeToEdge()
        setContent {
            MyAdBlockerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onToggleVpn = { currentlyRunning ->
                            if (currentlyRunning) {
                                AdBlockerVpnService.stop(this)
                            } else {
                                requestAndStartVpn()
                            }
                        },
                        onResetStats = {
                            AdBlockerState.resetStats(this)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AdBlockerState.syncLogsForUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_REQUEST_VPN) {
            requestAndStartVpn()
        }
    }

    private fun requestAndStartVpn() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            AdBlockerVpnService.start(this)
        } else {
            vpnPermissionLauncher.launch(prepareIntent)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val ACTION_REQUEST_VPN = "com.example.myadblocker.ACTION_REQUEST_VPN"
    }
}
