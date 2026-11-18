package dev.xiaoman.hands

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * User takeover of the phone from the status overlay. While paused every device action waits at
 * [awaitResumed], so actions planned before the takeover are not injected while the user is
 * operating the screen. Nothing resumes on its own: only [setPaused] false does.
 */
class TakeoverControl {
    private val _paused = MutableStateFlow(false)

    /** True while the user is operating the phone and automatic operation is suspended. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    @Volatile
    private var interrupted = false

    /** Pauses or resumes automatic operation; resuming lets waiting actions continue. */
    fun setPaused(paused: Boolean) {
        if (!paused) interrupted = false
        _paused.value = paused
    }

    /** Wakes a waiting action because the task was stopped; the action must not run. */
    fun interrupt() {
        interrupted = true
        _paused.value = false
    }

    /** Clears both states when a new task starts. */
    fun reset() {
        interrupted = false
        _paused.value = false
    }

    /** Suspends while the user has taken over; false when the task was stopped in the meantime. */
    suspend fun awaitResumed(): Boolean {
        if (interrupted) return false
        _paused.first { !it || interrupted }
        return !interrupted
    }
}
