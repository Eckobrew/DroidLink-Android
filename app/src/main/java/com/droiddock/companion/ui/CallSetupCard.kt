package com.droiddock.companion.ui

import android.app.Activity
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
@Suppress("DEPRECATION")
fun CallSetupCard() {
    val context = LocalContext.current
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }

    val telecomManager = remember { context.getSystemService(TelecomManager::class.java) }
    var hasCallPermission by remember {
        mutableStateOf(telecomManager?.hasManageOngoingCallsPermission() == true)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        val device = if (Build.VERSION.SDK_INT >= 33) {
            it.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
        } else {
            it.data?.getParcelableExtra<android.bluetooth.BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)
        }
        if (device != null && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            context.getSharedPreferences("call_setup", Context.MODE_PRIVATE).edit().putString("macAddress", device.address).apply()
        }
        val ready = telecomManager?.hasManageOngoingCallsPermission() == true
        hasCallPermission = ready
        if (it.resultCode != Activity.RESULT_OK) {
            isSuccess = false
            statusMessage = "Call setup was cancelled."
        } else if (ready) {
            isSuccess = true
            statusMessage = "Call controls enabled! Ensure this Mac is also paired in Bluetooth settings for audio."
        } else {
            isSuccess = true
            statusMessage = "Mac linked. Please reopen the companion app to complete enabling call controls."
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Mac call controls",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (hasCallPermission) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Active",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "Answer, dial, and manage telephone calls from your Mac",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalButton(
                    onClick = {
                        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                            isSuccess = false
                            statusMessage = "This phone does not support companion device setup."
                        } else {
                            val setup = context.getSharedPreferences("call_setup", Context.MODE_PRIVATE)
                            val filter = BluetoothDeviceFilter.Builder().apply {
                                setup.getString("macAddress", null)?.let { setAddress(it) }
                            }.build()
                            val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
                            try {
                                context.getSystemService(CompanionDeviceManager::class.java).associate(
                                    request,
                                    object : CompanionDeviceManager.Callback() {
                                        override fun onDeviceFound(chooserLauncher: IntentSender) {
                                            launcher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                                        }
                                        override fun onFailure(error: CharSequence?) {
                                            isSuccess = false
                                            statusMessage = "Could not find Mac. Ensure Bluetooth is enabled and discoverable on your Mac."
                                        }
                                    },
                                    Handler(Looper.getMainLooper())
                                )
                            } catch (e: Exception) {
                                isSuccess = false
                                statusMessage = "Call setup could not start: ${e.message}"
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(if (hasCallPermission) "Reconfigure" else "Enable", fontSize = 13.sp)
                }
            }

            AnimatedVisibility(visible = statusMessage != null) {
                statusMessage?.let { msg ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSuccess) Color(0xFF10B981).copy(alpha = 0.12f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (isSuccess) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = msg,
                                fontSize = 12.sp,
                                color = if (isSuccess) Color(0xFF10B981) else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}
