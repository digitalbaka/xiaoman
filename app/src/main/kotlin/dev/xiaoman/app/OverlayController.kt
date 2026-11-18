package dev.xiaoman.app

import android.content.Intent
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.hands.AccessibilityDevice
import dev.xiaoman.hands.XiaomanAccessibilityService
import dev.xiaoman.hands.StatusOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Shows the hands' [StatusOverlay] (step text + Stop) while the session is busy and the
 * accessibility service is connected; the overlay must be created with the connected service.
 */
class OverlayController(
    private val session: AgentSession,
    private val device: AccessibilityDevice,
    private val services: StateFlow<XiaomanAccessibilityService?> = XiaomanAccessibilityService.instance,
    private val uiVisible: StateFlow<Boolean> = MutableStateFlow(false),
) {
    private var overlay: StatusOverlay? = null
    private var owner: XiaomanAccessibilityService? = null

    private data class OverlayState(
        val service: XiaomanAccessibilityService?,
        val busy: Boolean,
        val status: String?,
        val paused: Boolean,
    )

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.Main.immediate) {
        combine(services, session.busy, session.status, uiVisible, session.takeover.paused) { service, busy, status, visible, paused ->
            OverlayState(service, busy && !visible, status, paused)
        }.collect { state ->
            val service = state.service
            if (!state.busy || service == null) {
                remove()
                return@collect
            }
            if (owner !== service) {
                remove()
                overlay = StatusOverlay(service).also {
                    it.onStop = { session.stop() }
                    it.onTakeover = { session.takeover.setPaused(!session.takeover.paused.value) }
                    it.onOpen = { service.startActivity(Intent(service, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                    device.overlay = it
                    it.show()
                }
                owner = service
            }
            overlay?.setPaused(state.paused)
            overlay?.update(if (state.paused) service.getString(R.string.overlay_taken_over) else state.status ?: "小满Extra")
        }
    }

    private fun remove() {
        overlay?.hide()
        overlay = null
        owner = null
        device.overlay = null
    }
}
