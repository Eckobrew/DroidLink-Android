package com.droiddock.companion.ui

import android.app.Activity
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
@Suppress("DEPRECATION")
fun CallSetupCard() {
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
            it.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
        } else {
            it.data?.getParcelableExtra<android.bluetooth.BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)
        }
        if (device != null && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            context.getSharedPreferences("call_setup", Context.MODE_PRIVATE).edit().putString("macAddress", device.address).apply()
        }
        val ready = context.getSystemService(TelecomManager::class.java).hasManageOngoingCallsPermission()
        result = when {
            it.resultCode != Activity.RESULT_OK -> "Call setup was cancelled."
            ready -> "Call controls enabled. Also pair this Mac in Bluetooth settings and enable Phone calls for audio."
            else -> "Mac linked. Reopen the app to finish enabling call controls."
        }
    }
    ServiceStatusRow(
        icon = Icons.Default.Phone,
        title = "Mac call controls",
        subtitle = "Enable answering and managing calls from your Mac.",
        actionLabel = "Enable call controls",
        onAction = {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                result = "This phone does not support companion call-control setup."
            } else {
                val setup = context.getSharedPreferences("call_setup", Context.MODE_PRIVATE)
                val filter = BluetoothDeviceFilter.Builder().apply {
                    setup.getString("macAddress", null)?.let { setAddress(it) }
                }.build()
                val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
                try {
                    context.getSystemService(CompanionDeviceManager::class.java).associate(request,
                        object : CompanionDeviceManager.Callback() {
                            override fun onDeviceFound(chooserLauncher: IntentSender) {
                                launcher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                            }
                            override fun onFailure(error: CharSequence?) {
                                result = "Could not find the Mac. Open Bluetooth settings on the Mac and try again."
                            }
                        }, Handler(Looper.getMainLooper()))
                } catch (e: Exception) { result = "Call setup could not start. Please try again." }
            }
        }
    )
    result?.let { Text(it) }
}
