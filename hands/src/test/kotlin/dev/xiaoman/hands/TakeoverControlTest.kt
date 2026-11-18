package dev.xiaoman.hands

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TakeoverControlTest {
    @Test
    fun waitsWhilePausedAndResumesOnDemand() = runTest {
        val control = TakeoverControl()
        control.setPaused(true)
        var resumed: Boolean? = null
        val waiting = launch { resumed = control.awaitResumed() }
        advanceUntilIdle()
        assertTrue(control.paused.value)
        assertFalse(waiting.isCompleted)
        assertTrue(resumed == null)

        control.setPaused(false)
        waiting.join()
        assertTrue(resumed == true)
    }

    @Test
    fun stoppingWakesTheActionWithoutRunningIt() = runTest {
        val control = TakeoverControl()
        control.setPaused(true)
        var resumed: Boolean? = null
        val waiting = launch { resumed = control.awaitResumed() }
        advanceUntilIdle()

        control.interrupt()
        waiting.join()
        assertTrue(resumed == false)
        assertFalse(control.paused.value)
    }

    @Test
    fun aNewTaskClearsBothStates() {
        val control = TakeoverControl()
        control.setPaused(true)
        control.interrupt()
        control.reset()
        assertFalse(control.paused.value)
    }
}
