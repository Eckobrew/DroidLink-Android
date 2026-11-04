package com.droiddock.companion.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Base64
import android.util.Log
import com.droiddock.companion.network.InstalledApp
import java.io.ByteArrayOutputStream

class AppsHelper(private val context: Context) {

    companion object {
        private const val TAG = "DroidDockApps"
    }

    fun getAllInstalledApps(): List<InstalledApp> {
        val appList = mutableListOf<InstalledApp>()
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val seenPackages = mutableSetOf<String>()

        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            if (seenPackages.contains(pkg)) continue
            seenPackages.add(pkg)

            try {
                val name = info.loadLabel(pm).toString()
                val isSystem = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val pInfo = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkg, 0)
                    }
                } catch (e: Exception) {
                    null
                }

                val versionName = pInfo?.versionName
                val iconDrawable = try {
                    info.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }

                // Compress icon to 96x96 PNG Base64
                val iconBase64 = iconDrawable?.let { drawableToBase64(it, 96, 96) }

                appList.add(
                    InstalledApp(
                        id = pkg,
                        name = name,
                        packageName = pkg,
                        versionName = versionName,
                        iconBase64 = iconBase64,
                        isSystemApp = isSystem
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error processing app $pkg: ${e.message}")
            }
        }

        appList.sortBy { it.name.lowercase() }
        Log.d(TAG, "Found ${appList.size} launchable installed apps on device")
        return appList
    }

    private fun drawableToBase64(drawable: Drawable, width: Int, height: Int): String? {
        return try {
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                Bitmap.createScaledBitmap(drawable.bitmap, width, height, true)
            } else {
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 85, baos)
            val bytes = baos.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Error converting icon to base64: ${e.message}")
            null
        }
    }
}
