package com.droiddock.companion.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddock.companion.network.ConnectionManager

@Composable
fun DashboardScreen(
    onUnpair: () -> Unit
) {
    val context = LocalContext.current
    val connMgr = remember { ConnectionManager.getInstance(context) }
    val connectionState by connMgr.transport.collectAsState()
    val bluetoothStatus by connMgr.bluetoothStatus.collectAsState()
    var fallbackEnabled by remember { mutableStateOf(connMgr.bluetoothFallbackEnabled) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Connection Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = if (connectionState != "disconnected") Color(0xFF2E7D32) else Color(0xFFD32F2F),
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = if (connectionState != "disconnected") "Linked with Mac" else "Disconnected",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = if (connectionState != "disconnected") "Active Transport: ${connectionState.uppercase()}" else "Status: Waiting for Wi-Fi or Bluetooth",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = if (connectionState != "disconnected") "End-to-End Encryption: ECDH P-256 + AES-256-GCM Active" else "Keep the Mac app open; enable Nearby Devices for Bluetooth fallback",
                    fontSize = 12.sp,
                    color = if (connectionState != "disconnected") Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Bluetooth data fallback", fontWeight = FontWeight.Medium)
                Text(bluetoothStatus, fontSize = 12.sp)
            }
            TextButton(onClick = { connMgr.retryBluetoothConnection() }, enabled = fallbackEnabled) { Text("Retry") }
            Switch(checked = fallbackEnabled, onCheckedChange = {
                fallbackEnabled = it
                connMgr.setBluetoothFallbackEnabled(it)
            })
        }

        Text(
            text = "Companion Hub Services",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        // RCS Service Card
        ServiceStatusRow(
            icon = Icons.Default.Chat,
            title = "RCS & SMS Companion Bridge",
            subtitle = "Captures carrier RCS chats and enables Mac replies",
            actionLabel = "Notification Access",
            onAction = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )

        CallSetupCard()

        OutlinedButton(onClick = {
            context.startActivity(Intent(context, com.droiddock.companion.messages.ImageComposeActivity::class.java))
        }) { Text("Open pending image message") }

        // Handset Audio Routing Card
        ServiceStatusRow(
            icon = Icons.Default.Headphones,
            title = "Bluetooth Audio Gateway",
            subtitle = "Mac acts as Hands-Free handset for in-call voice",
            actionLabel = "Bluetooth",
            onAction = {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                connMgr.sendDeviceStatus()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Send Sync Ping to Mac")
        }

        Button(
            onClick = onUnpair,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Unpair Device")
        }
    }
}

@Composable
fun ServiceStatusRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(text = subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            TextButton(onClick = onAction) {
                Text(actionLabel, fontSize = 12.sp)
            }
        }
    }
}
