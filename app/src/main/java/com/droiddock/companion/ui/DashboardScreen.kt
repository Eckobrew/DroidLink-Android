package com.droiddock.companion.ui

import android.content.Intent
import android.provider.Settings
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
    var connectionState by remember { mutableStateOf("wifi") }

    LaunchedEffect(Unit) {
        connMgr.onConnectionStateChanged = { state ->
            connectionState = state
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
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
                    text = "Active Transport: ${connectionState.uppercase()} with Bluetooth fallback",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "End-to-End Encryption: ECDH P-256 + AES-256-GCM Active",
                    fontSize = 12.sp,
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Medium
                )
            }
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

        // Phone Calls & Telecom Card
        ServiceStatusRow(
            icon = Icons.Default.Phone,
            title = "Bluetooth Telephony Handset",
            subtitle = "Answers and initiates calls from your Mac",
            actionLabel = "Telecom Settings",
            onAction = {
                context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        )

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

        Spacer(modifier = Modifier.weight(1f))

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
