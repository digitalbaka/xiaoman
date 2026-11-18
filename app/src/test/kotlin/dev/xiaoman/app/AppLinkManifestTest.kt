package dev.xiaoman.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guards the manifest side of the deep links: the filter has to be registered on MainActivity,
 * otherwise the system answers "No Activity" before [AppLink.parse] ever runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLinkManifestTest {

    private fun resolve(url: String) = RuntimeEnvironment.getApplication().packageManager
        .resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addCategory(Intent.CATEGORY_DEFAULT),
            0,
        )
        ?.activityInfo
        ?.let { ComponentName(it.packageName, it.name) }

    private fun assertHandledByApp(url: String) {
        assertEquals(MainActivity::class.java.name, resolve(url)?.className)
    }

    @Test
    fun `alarm link is registered`() {
        assertHandledByApp("xiaoman://alarm/set?time=07%3A30&label=wake")
    }

    @Test
    fun `chat add links are registered`() {
        assertHandledByApp("yunhu://chat-add?id=442186744&type=group")
        assertHandledByApp("yunhu://chat-add?id=9127677&type=user")
        assertHandledByApp("yunhu://chat-add?id=91276778&type=bot")
    }

    @Test
    fun `post detail link is registered`() {
        assertHandledByApp("yunhu://post-detail?id=863")
    }
}
