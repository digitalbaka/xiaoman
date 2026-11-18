package dev.xiaoman.hands

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri

/** How the system answered a link handed to [DeepLinkDispatcher]. */
sealed class DeepLinkResult {
    /** The only handler, or the user's default handler, opened the link. */
    data class Opened(val handler: String?) : DeepLinkResult()

    /** Several apps could open it and no default was set; the chooser is showing. */
    data class Chooser(val count: Int) : DeepLinkResult()

    /** Nothing handled the scheme; a browser or store search was opened instead. */
    data class Fallback(val target: String) : DeepLinkResult()

    /** Nothing could open it; [message] is readable and names the scheme instead of "No Activity". */
    data class Unsupported(val message: String) : DeepLinkResult()
}

/**
 * Opens any `scheme://host/path?query` through the system, without a scheme whitelist. The original
 * URI is handed to ACTION_VIEW untouched; only the fallbacks build new URIs. Handlers are resolved
 * first, so a missing app produces a readable reason instead of an ActivityNotFoundException.
 *
 * No QUERY_ALL_PACKAGES: the manifest declares a small `<queries>` set. Queries therefore only see
 * the declared apps, so an empty result is not proof that nothing handles the link — the implicit
 * start is attempted next, and only its ActivityNotFoundException falls through to the fallbacks.
 */
class DeepLinkDispatcher(context: Context) {
    private val pm = context.applicationContext.packageManager

    /** Opens [raw]; [starter] should be the accessibility service so background starts are allowed. */
    fun open(raw: String, packageName: String? = null, starter: Context): DeepLinkResult {
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || scheme.isNullOrBlank()) return DeepLinkResult.Unsupported("链接无效：$raw")

        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        packageName?.takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }

        val candidates = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        if (candidates.isEmpty()) {
            // Invisible to queries: let the system resolve it. Only a real miss reaches the fallback.
            val implicitStart = start(starter, intent, null)
            return if (implicitStart is DeepLinkResult.Opened) implicitStart else fallback(uri, scheme, starter, packageName)
        }
        if (candidates.size == 1) {
            val info = candidates.first().activityInfo
            intent.setClassName(info.packageName, info.name)
            return start(starter, intent, info.packageName)
        }
        // Several handlers: the default one opens directly, otherwise the user picks.
        val default = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
        if (!isResolver(default)) return start(starter, intent, default?.activityInfo?.packageName)
        return chooser(starter, intent, candidates.size)
    }

    /** The system resolver has no real package of its own: treat it as "no default chosen". */
    private fun isResolver(info: ResolveInfo?): Boolean {
        val activity = info?.activityInfo ?: return true
        return activity.packageName == "android" ||
            activity.packageName.contains("intentresolver", ignoreCase = true) ||
            activity.name.contains("Resolver", ignoreCase = true)
    }

    private fun start(starter: Context, intent: Intent, handler: String?): DeepLinkResult = try {
        starter.startActivity(intent)
        DeepLinkResult.Opened(handler)
    } catch (e: ActivityNotFoundException) {
        DeepLinkResult.Unsupported("没有应用能打开这个链接：${intent.data}")
    } catch (e: SecurityException) {
        DeepLinkResult.Unsupported("系统不允许打开这个链接：${e.message ?: intent.data}")
    }

    private fun chooser(starter: Context, intent: Intent, count: Int): DeepLinkResult = try {
        starter.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        DeepLinkResult.Chooser(count)
    } catch (e: ActivityNotFoundException) {
        DeepLinkResult.Unsupported("没有应用能打开这个链接：${intent.data}")
    } catch (e: SecurityException) {
        DeepLinkResult.Unsupported("系统不允许打开这个链接：${e.message ?: intent.data}")
    }

    /**
     * No handler. Web links go to a known browser; custom schemes usually mean a missing app, so a
     * store search for the scheme is offered, then the Play search page in a browser. Only here are
     * new URIs built — the link itself is never rewritten.
     */
    private fun fallback(uri: Uri, scheme: String, starter: Context, packageName: String?): DeepLinkResult {
        if (packageName != null) return DeepLinkResult.Unsupported("应用 $packageName 无法打开 $scheme:// 链接")

        if (scheme == "http" || scheme == "https") {
            return if (openInBrowser(starter, uri)) {
                DeepLinkResult.Fallback("浏览器")
            } else {
                DeepLinkResult.Unsupported("手机上还没有能打开网页的浏览器")
            }
        }

        val search = Uri.parse("market://search?q=" + Uri.encode(scheme))
        for (store in STORES) {
            val intent = Intent(Intent.ACTION_VIEW, search)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setPackage(store)
            if (start(starter, intent, store) is DeepLinkResult.Opened) {
                return DeepLinkResult.Fallback("应用商店（搜索 $scheme）")
            }
        }
        val web = Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(scheme))
        if (openInBrowser(starter, web)) return DeepLinkResult.Fallback("浏览器（应用商店搜索 $scheme）")
        return DeepLinkResult.Unsupported("没有应用能打开 $scheme:// 链接，请先安装对应的 App 后重试")
    }

    /** Browsers declared in the manifest `<queries>`; only http(s) URIs are handed to them. */
    private fun openInBrowser(starter: Context, uri: Uri): Boolean = BROWSERS.any { browser ->
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setPackage(browser)
        start(starter, intent, browser) is DeepLinkResult.Opened
    }

    private companion object {
        val BROWSERS = listOf(
            "com.android.chrome",
            "com.android.browser",
            "com.microsoft.emmx",
            "com.tencent.mtt",
            "com.UCMobile",
            "com.quark.browser",
            "com.baidu.searchbox",
        )
        val STORES = listOf(
            "com.android.vending",
            "com.xiaomi.market",
            "com.huawei.appmarket",
            "com.oppo.market",
            "com.heytap.market",
            "com.bbk.appstore",
            "com.tencent.android.qqdownloader",
        )
    }
}
