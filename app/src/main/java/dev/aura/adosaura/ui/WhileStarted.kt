package dev.aura.adosaura.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope

/**
 * A LaunchedEffect that only runs while the launcher is on screen: cancelled
 * when it stops, started again when it returns. A plain LaunchedEffect keeps
 * polling after Home is left, and Android kills a background process that
 * burns CPU, so the next Home press is a cold start that feels frozen.
 */
@Composable
fun WhileStarted(key: Any? = Unit, block: suspend CoroutineScope.() -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, key) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED, block) }
}
