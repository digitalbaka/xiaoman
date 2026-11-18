package dev.xiaoman.hands

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A small pill at the top of the screen showing the current step, a Take over / Resume button and
 * a Stop button. Taking over pauses automatic operation until the user taps Resume.
 *
 * [context] must be the connected [XiaomanAccessibilityService] (or a context derived from it):
 * the window is TYPE_ACCESSIBILITY_OVERLAY, which only an accessibility service may add and which
 * needs no SYSTEM_ALERT_WINDOW permission. The window wraps its content and is not focusable, so
 * touches outside the pill reach the app underneath.
 *
 * All methods may be called from any thread.
 */
class StatusOverlay(private val context: Context) {

    /** Invoked on the main thread when the user taps Stop. */
    var onStop: () -> Unit = {}

    /** Invoked on the main thread when the user takes over the phone or resumes 小满. */
    var onTakeover: () -> Unit = {}
    var onOpen: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var textView: TextView? = null
    private var takeoverButton: Button? = null
    private var pendingText: CharSequence = ""
    private var paused = false

    val isShowing: Boolean get() = root?.isAttachedToWindow == true

    fun show() = onMain {
        if (root != null) return@onMain
        val view = buildView()
        try {
            windowManager.addView(view, layoutParams())
            root = view
        } catch (e: RuntimeException) {
            // BadTokenException / WindowManager errors when the service is not connected.
            android.util.Log.w(TAG, "cannot show overlay: ${e.message}")
            root = null
            textView = null
        }
    }

    fun update(text: CharSequence) = onMain {
        pendingText = text
        textView?.text = text
    }

    /** Switches the takeover button between "接管" and "继续" and recolors it. */
    fun setPaused(paused: Boolean) = onMain {
        this.paused = paused
        applyTakeoverState()
    }

    fun hide() = onMain {
        val view = root ?: return@onMain
        root = null
        textView = null
        takeoverButton = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    /**
     * Hides the overlay and disables its window's touch interception while [block] runs,
     * then restores both. View.GONE alone does not remove the WindowManager input surface.
     * Waits [settleMs] after hiding for the frame and input window update to be applied.
     */
    suspend fun <T> hiddenWhile(settleMs: Long = 80, block: suspend () -> T): T {
        val hidden = withContext(Dispatchers.Main.immediate) {
            val view = root
            if (view != null && view.visibility == View.VISIBLE) {
                val params = view.layoutParams as WindowManager.LayoutParams
                val flags = params.flags
                params.flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager.updateViewLayout(view, params)
                view.visibility = View.GONE
                view to flags
            } else null
        }
        if (hidden == null) return block()
        try {
            delay(settleMs)
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                val (view, flags) = hidden
                // hide() clears root before removing the window. Identity is sufficient here;
                // attachment may lag while WindowManager is applying the layout update.
                if (root === view) {
                    val params = view.layoutParams as WindowManager.LayoutParams
                    params.flags = flags
                    view.visibility = View.VISIBLE
                    windowManager.updateViewLayout(view, params)
                }
            }
        }
    }

    private fun buildView(): LinearLayout {
        val pad = dp(10)
        val label = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.42f).toInt()
            text = pendingText
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "查看小满的任务"
            setOnClickListener { onOpen() }
            // Read status changes to TalkBack users.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val takeover = actionButton().apply {
            setOnClickListener { onTakeover() }
        }
        val stop = actionButton().apply {
            setText(R.string.xiaoman_overlay_stop)
            contentDescription = context.getString(R.string.xiaoman_overlay_stop_description)
            background = pill(STOP_COLOR)
            setOnClickListener { onStop() }
        }
        textView = label
        takeoverButton = takeover
        applyTakeoverState()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad / 2, pad / 2, pad / 2)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xF01E4635.toInt())
            }
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = pad })
            addView(takeover, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
            addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * Both pill buttons share one spec so they keep the same size and visual weight: a 60dp minimum
     * width (two CJK characters), 40dp height, 12dp side padding and one corner radius. Only the
     * fill colour changes between Take over (amber), Resume (green) and Stop (neutral).
     */
    private fun actionButton(): Button = Button(context).apply {
        isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        minWidth = dp(60)
        minimumWidth = dp(60)
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(12), 0, dp(12), 0)
        // Platform button style adds its own elevation/ripple tint; the pill is drawn by hand.
        stateListAnimator = null
        backgroundTintList = null
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(color)
    }

    /** Take over = amber (小满 is driving), Resume = green (hands back to 小满). */
    private fun applyTakeoverState() {
        takeoverButton?.apply {
            setText(if (paused) R.string.xiaoman_overlay_resume else R.string.xiaoman_overlay_takeover)
            contentDescription = context.getString(
                if (paused) R.string.xiaoman_overlay_resume_description else R.string.xiaoman_overlay_takeover_description,
            )
            background = pill(if (paused) 0xF02E7D32.toInt() else 0xF0B26A00.toInt())
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(36)
        title = "Xiaoman status"
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val TAG = "Xiaoman"

        /** Neutral fill for Stop, so it weighs the same as the coloured Take over / Resume pill. */
        const val STOP_COLOR = 0x33FFFFFF
    }
}
