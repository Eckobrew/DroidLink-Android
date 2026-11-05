package com.droiddock.companion.mirroring

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MirrorConsentActivity : ComponentActivity() {
    private var sessionId = ""
    private var audio = false
    private var launched = false
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null && MirrorCoordinator.get(sessionId) != null) {
            val service = Intent(this, ScreenCaptureService::class.java)
                .putExtra("sessionId", sessionId).putExtra("resultCode", result.resultCode)
                .putExtra("resultData", result.data).putExtra("audio", audio)
            try { ContextCompat.startForegroundService(this, service) }
            catch (e: Exception) { MirrorCoordinator.stop(this, sessionId, "Could not start screen sharing: ${e.message}") }
        } else MirrorCoordinator.stop(this, sessionId, "Screen sharing was declined")
        finish()
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audio = granted
        requestCapture()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra("sessionId") ?: ""
        val request = MirrorCoordinator.get(sessionId) ?: run { finish(); return }
        audio = savedInstanceState?.getBoolean("audio") ?: request.offer.audio
        launched = savedInstanceState?.getBoolean("launched") ?: false
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp).statusBarsPadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("Share with your Mac", style = MaterialTheme.typography.headlineMedium)
                        Text("Your paired Mac will see your screen until you stop sharing. Android will ask you to confirm before sharing begins.")
                        var shareAudio by remember { mutableStateOf(audio) }
                        Row { Checkbox(shareAudio, { shareAudio = it; audio = it }); Text("Include supported app audio", Modifier.padding(top = 12.dp)) }
                        Text("With remote control enabled, Droid Link keeps the phone awake while sharing. You can dim it from the Mac. Locking the phone ends sharing and requires approval again.")
                        Text("Remote control is optional. Enable Droid Link in Accessibility settings to allow taps, swipes and text entry while sharing.")
                        OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Enable remote control") }
                        Button(enabled = !launched, onClick = {
                            launched = true
                            if (audio && ContextCompat.checkSelfPermission(this@MirrorConsentActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                                microphone.launch(Manifest.permission.RECORD_AUDIO)
                            else requestCapture()
                        }) { Text("Start sharing") }
                        TextButton(onClick = { MirrorCoordinator.stop(this@MirrorConsentActivity, sessionId, "Screen sharing was declined"); finish() }) { Text("Cancel") }
                    }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("audio", audio); outState.putBoolean("launched", launched)
        super.onSaveInstanceState(outState)
    }
    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
        capture.launch(intent)
    }
}
