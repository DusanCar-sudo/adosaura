package dev.aura.adosaura.lockskin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Palette
import dev.aura.adosaura.ui.rememberTick
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The lock-screen skin: the clock, drawn over the keyguard.
 *
 * `setShowWhenLocked` is the whole trick — it puts this activity above the
 * keyguard without bypassing it. The phone is still locked: no PIN is skipped,
 * nothing here reads what is behind the lock, and the only thing you can do is
 * look at the time and hand control back.
 *
 * That limit is deliberate. Launching apps from here would be a lock-screen
 * bypass, so there are none — no tiles that open anything, no drawer, no
 * recents. When the user actually unlocks, the keyguard broadcasts
 * `ACTION_USER_PRESENT` and ScreenWatcher calls [requestDismiss], so the skin
 * never lingers over an unlocked phone.
 */
class LockOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        current = this
        setContent { LockSkin(onDismiss = { finish() }) }
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: LockOverlayActivity? = null

        /** Called when the keyguard is gone: the skin has no job left. */
        fun requestDismiss() {
            current?.runOnUiThread { current?.finish() }
        }
    }
}

@Composable
private fun LockSkin(onDismiss: () -> Unit) {
    val tick = rememberTick()
    val now = Instant.ofEpochMilli(tick).atZone(ZoneId.systemDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.current.bg)
            .clickable(onClick = onDismiss),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = now.toLocalTime().format(HOUR_MINUTE),
            color = Palette.current.fgBright,
            fontSize = 64.sp,
            fontFamily = AdosauraMono,
        )
        Text(
            text = now.toLocalDate().format(DATE_LINE),
            color = Palette.current.muted,
            fontSize = 15.sp,
            fontFamily = AdosauraMono,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = LOCK_HINT,
            color = Palette.current.dim,
            fontSize = 13.sp,
            fontFamily = AdosauraMono,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp),
        )
    }
}

private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_LINE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private const val LOCK_HINT = "tap to unlock"
