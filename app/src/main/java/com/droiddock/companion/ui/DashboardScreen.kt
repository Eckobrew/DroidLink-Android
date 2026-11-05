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
                    text = if (connectionState != "disconnected") if (connectionState == "wifi") "Connected over Wi-Fi" else "Connected over Bluetooth" else "Looking for your Mac",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = if (connectionState != "disconnected") "Your connection is end-to-end encrypted" else "Keep Droid Link open on your Mac",
                    fontSize = 12.sp,
                    color = if (connectionState != "disconnected") Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Bluetooth fallback", fontWeight = FontWeight.Medium)
                Text("Stay connected when Wi-Fi is unavailable", fontSize = 12.sp)
            }
            TextButton(onClick = { connMgr.retryBluetoothConnection() }, enabled = fallbackEnabled) { Text("Retry") }
            Switch(checked = fallbackEnabled, onCheckedChange = {
                fallbackEnabled = it
                connMgr.setBluetoothFallbackEnabled(it)
            })
        }

        Text(
            text = "Settings",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        // RCS Service Card
        ServiceStatusRow(
            icon = Icons.Default.Chat,
            title = "Messages & notifications",
            subtitle = "Allow messages, images and notifications to appear on your Mac",
            actionLabel = "Notification Access",
            onAction = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )

        ServiceStatusRow(
            icon = Icons.Default.Notifications,
            title = "Connection notification",
            subtitle = "Show your connection status while the app runs in the background",
            actionLabel = "Manage",
            onAction = {
                context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, com.droiddock.companion.DroidDockApplication.CHANNEL_ID))
            }
        )

        CallSetupCard()

        OutlinedButton(onClick = {
            context.startActivity(Intent(context, com.droiddock.companion.messages.ImageComposeActivity::class.java))
        }) { Text("Open pending image message") }

        // Handset Audio Routing Card
        ServiceStatusRow(
            icon = Icons.Default.Headphones,
            title = "Call audio",
            subtitle = "Pair your Mac to use its speakers and microphone for calls",
            actionLabel = "Bluetooth",
            onAction = {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

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
