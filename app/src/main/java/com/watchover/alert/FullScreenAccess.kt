package com.watchover.alert

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Whether the platform will actually honour a full-screen intent.
 *
 * `NotificationManager.canUseFullScreenIntent()` is the documented check, but on
 * Android 17 it reported "allowed" while the underlying app-op was still at its
 * default (denied) — the alert was then posted with the intent stripped. The
 * app-op is the state the system enforces, so it is checked first and the
 * documented API is only the fallback.
 */
object FullScreenAccess {

    private const val OP_USE_FULL_SCREEN_INTENT = "android:use_full_screen_intent"

    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        if (appOps != null) {
            val mode = runCatching {
                appOps.unsafeCheckOpNoThrow(
                    OP_USE_FULL_SCREEN_INTENT,
                    Process.myUid(),
                    context.packageName,
                )
            }.getOrNull()
            if (mode != null) return mode == AppOpsManager.MODE_ALLOWED
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.getSystemService(NotificationManager::class.java)
            ?.canUseFullScreenIntent() ?: false
    }
}
