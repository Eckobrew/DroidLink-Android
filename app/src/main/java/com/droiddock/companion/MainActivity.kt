package com.droiddock.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.droiddock.companion.crypto.SecureStorage
import com.droiddock.companion.ui.DashboardScreen
import com.droiddock.companion.ui.PairingScreen
import com.droiddock.companion.ui.theme.DroidDockTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions evaluated
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()

        val secureStorage = SecureStorage(this)
        if (secureStorage.getStoredPeerPublicKey() != null) startConnectionService()

        setContent {
            DroidDockTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                    ) {
                        var isPaired by remember {
                            mutableStateOf(secureStorage.getStoredPeerPublicKey() != null)
                        }

                        if (isPaired) {
                            DashboardScreen(
                                onUnpair = {
                                    com.droiddock.companion.network.ConnectionManager.getInstance(this@MainActivity).unpair()
                                    isPaired = false
                                }
                            )
                        } else {
                            PairingScreen(
                                onPairingSuccess = {
                                    startConnectionService()
                                    isPaired = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (SecureStorage(this).getStoredPeerPublicKey() != null) startConnectionService()
    }

    private fun startConnectionService() {
        try {
            ContextCompat.startForegroundService(this, android.content.Intent(this,
                com.droiddock.companion.network.ConnectionService::class.java))
        } catch (e: Exception) { android.util.Log.w("DroidDock", "Connection service could not start", e) }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        }
    }
}
