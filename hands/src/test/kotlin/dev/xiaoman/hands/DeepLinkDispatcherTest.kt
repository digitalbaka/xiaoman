package dev.xiaoman.hands

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the dispatch outcomes with the manifest `<queries>` model (no QUERY_ALL_PACKAGES): only
 * declared packages are visible to queries, so an empty result must still try the implicit start
 * before falling back. The chooser (several visible handlers) is verified on a real device, where
 * `yunhu://` is handled by both this app and 云湖.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepLinkDispatcherTest {
    private val app = RuntimeEnvironment.getApplication()
    private val dispatcher = DeepLinkDispatcher(app)

    /** Mirrors the platform: an unresolvable intent throws instead of being recorded. */
    private inner class RecordingStarter : ContextWrapper(app) {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            val pkg = intent.`package`
            if (pkg != null && pkg != app.packageName) {
                try {
                    app.packageManager.getPackageInfo(pkg, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    throw ActivityNotFoundException(pkg)
                }
            } else if (intent.component == null && app.packageManager.resolveActivity(intent, 0) == null) {
                throw ActivityNotFoundException(intent.toString())
            }
            started += intent
        }
    }

    /** Installs [pkg] with one activity handling VIEW for the given [schemes]. */
    private fun install(pkg: String, activity: String, vararg schemes: String) {
        val pm = shadowOf(app.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = pkg })
        pm.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = pkg
                name = activity
                exported = true
                applicationInfo = ApplicationInfo().apply { packageName = pkg; flags = ApplicationInfo.FLAG_INSTALLED }
            },
        )
        val component = ComponentName(pkg, activity)
        schemes.forEach { scheme ->
            pm.addIntentFilterForActivity(
                component,
                IntentFilter(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addDataScheme(scheme)
                },
            )
        }
    }

    /** Registers one exact handler the way the original test did (ShadowPackageManager's map). */
    private fun handle(intent: Intent, pkg: String, activity: String) {
        shadowOf(app.packageManager).addResolveInfoForIntent(
            intent,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = pkg
                    name = activity
                    exported = true
                }
            },
        )
    }

    @Test
    fun unparseableLinkFailsReadably() {
        val result = assertIs<DeepLinkResult.Unsupported>(dispatcher.open("not a uri", starter = app))
        assertTrue(result.message.contains("链接无效"))
    }

    @Test
    fun opensTheOnlyHandlerWithTheOriginalUri() {
        handle(Intent(Intent.ACTION_VIEW, Uri.parse("demo://open/1?id=7")), "com.demo", "com.demo.Main")
        val starter = RecordingStarter()

        val result = assertIs<DeepLinkResult.Opened>(dispatcher.open("demo://open/1?id=7", starter = starter))

        assertEquals("com.demo", result.handler)
        val started = starter.started.single()
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("demo://open/1?id=7", started.data.toString())
    }

    @Test
    fun unknownSchemeFallsBackToAStoreSearch() {
        install("com.xiaomi.market", "com.xiaomi.market.Main", "market")
        val starter = RecordingStarter()

        val result = assertIs<DeepLinkResult.Fallback>(dispatcher.open("nosuchapp://open?id=1", starter = starter))

        assertTrue(result.target.contains("应用商店"))
        val started = starter.started.last()
        assertEquals("market://search?q=nosuchapp", started.data.toString())
        assertEquals("com.xiaomi.market", started.`package`)
    }

    @Test
    fun unknownSchemeWithoutStoreOpensTheStorePageInABrowser() {
        install("com.android.chrome", "com.android.chrome.Main", "http", "https")
        val starter = RecordingStarter()

        val result = assertIs<DeepLinkResult.Fallback>(dispatcher.open("nosuchapp://open?id=1", starter = starter))

        assertTrue(result.target.contains("浏览器"))
        val started = starter.started.last()
        assertTrue(started.data.toString().startsWith("https://play.google.com/store/search"))
        assertEquals("com.android.chrome", started.`package`)
    }

    @Test
    fun webLinkWithoutBrowserGivesAReadableError() {
        val result = assertIs<DeepLinkResult.Unsupported>(
            dispatcher.open("https://example.com/a", starter = RecordingStarter()),
        )

        assertTrue(result.message.contains("浏览器"))
        assertTrue(!result.message.contains("No Activity"))
    }

    @Test
    fun schemeWithoutAnyHandlerGivesAReadableError() {
        val result = assertIs<DeepLinkResult.Unsupported>(
            dispatcher.open("nosuchapp://open?id=1", starter = RecordingStarter()),
        )

        assertTrue(result.message.contains("nosuchapp"))
        assertTrue(!result.message.contains("No Activity"))
    }

    @Test
    fun packageScopedLinkReportsThePackage() {
        val result = assertIs<DeepLinkResult.Unsupported>(
            dispatcher.open("custom://x", packageName = "com.absent", starter = RecordingStarter()),
        )
        assertTrue(result.message.contains("com.absent"))
    }
}
