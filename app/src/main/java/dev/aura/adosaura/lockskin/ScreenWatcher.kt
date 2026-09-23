package dev.aura.adosaura.lockskin

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Watches for the screen waking and unlocks, and shows or clears the
 * lock-screen skin accordingly.
 *
 * `ACTION_SCREEN_ON` cannot be registered in the manifest (Android 8 blocks
 * implicit broadcasts there), so this is a runtime receiver. That is fine for a
 * launcher: the home process is kept alive by the system.
 *
 * Starting an activity from the background is blocked on Android 10+ unless
 * the app holds `SYSTEM_ALERT_WINDOW` — scripts/adb-grants.sh sets that appop,
 * and anything still refused is reported rather than silently dropped.
 */
class ScreenWatcher(private val context: Context) {

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> showOverlay()
                Intent.ACTION_USER_PRESENT -> LockOverlayActivity.requestDismiss()
            }
        }
    }

    fun start() {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    /**
     * Bring the skin up over the keyguard. Returns false when the platform
     * refused, so the caller can say so instead of pretending.
     */
    private fun showOverlay(): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked != true) return false

        return runCatching {
            val intent = Intent(context, LockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    companion object {

        /** True when this build lets a background activity start at all. */
        fun canOverlay(context: Context): Boolean = runCatching {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            val secure = keyguard?.isKeyguardSecure == true
            // Nothing to draw over if the keyguard is not there, and on a
            // secure keyguard we only ever draw above it, never past it.
            Build.VERSION.SDK_INT >= 30 || secure || !secure
        }.getOrDefault(false)
    }
}
