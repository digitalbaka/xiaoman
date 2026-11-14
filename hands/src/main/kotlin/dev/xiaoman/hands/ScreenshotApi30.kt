package dev.xiaoman.hands

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Android 11+ screenshot path. Kept in its own class so devices below API 30 never load code that
 * references [AccessibilityService.TakeScreenshotCallback], which only exists from API 30 and would
 * otherwise log a harmless ClassNotFoundException during class verification.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal object ScreenshotApi30 {
    class Attempt(val bitmap: Bitmap?, val errorCode: Int = 0)

    suspend fun capture(service: AccessibilityService): Attempt = suspendCancellableCoroutine { cont ->
        val direct = Executor { it.run() }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, direct, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hw ->
                            hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
                        }
                    } finally {
                        buffer.close()
                    }
                    if (cont.isActive) {
                        cont.resume(
                            Attempt(bitmap, if (bitmap == null) AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR else 0),
                        )
                    } else {
                        bitmap?.recycle()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(Attempt(null, errorCode))
                }
            })
        } catch (e: RuntimeException) {
            if (cont.isActive) cont.resume(Attempt(null, AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
        }
    }
}
