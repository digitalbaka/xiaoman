package dev.xiaoman.hands

import dev.xiaoman.api.Action
import dev.xiaoman.api.Point
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The user takeover must stop actions at the execution gate, before they reach the screen. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibiltyTakeoverTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun `queued action waits while the user has taken over`() = runTest {
        val takeover = TakeoverControl()
        val device = AccessibilityDevice({ null }, app, settleDelayMs = 0, takeover = takeover)

        takeover.setPaused(true)
        val queued = backgroundScope.async { device.perform(Action.Tap(point = Point(1, 1))) }
        advanceUntilIdle()
        assertFalse(queued.isCompleted) // still parked at the gate, nothing dispatched

        takeover.setPaused(false)
        advanceUntilIdle()
        // It only ran after Resume, and then hit the (absent) service.
        assertEquals(AccessibilityDevice.NOT_CONNECTED, queued.await().message)
    }

    @Test
    fun `stopping the task releases the gate without running the action`() = runTest {
        val takeover = TakeoverControl()
        val device = AccessibilityDevice({ null }, app, settleDelayMs = 0, takeover = takeover)

        takeover.setPaused(true)
        val queued = backgroundScope.async { device.perform(Action.Back()) }
        advanceUntilIdle()
        assertFalse(queued.isCompleted)

        takeover.interrupt()
        advanceUntilIdle()
        val result = queued.await()
        assertFalse(result.ok)
        assertEquals("已停止", result.message)
    }
}
