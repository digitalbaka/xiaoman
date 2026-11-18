package dev.xiaoman.app

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A link the app opens itself. Links are parsed here (an intent filter in the manifest decides
 * which ones reach [MainActivity]); everything else is dispatched to the system by the device layer.
 */
sealed class AppLink {
    /** `xiaoman://alarm/set?time=07:30&label=起床` */
    data class AlarmSet(val time: String?, val label: String?) : AppLink()

    /** `yunhu://chat-add?id=442186744&type=group` */
    data class ChatAdd(val id: String, val type: ChatTarget) : AppLink()

    /** `yunhu://post-detail?id=863` */
    data class PostDetail(val id: String) : AppLink()

    /** Conversation kinds a `chat-add` link can name; an unknown type falls back to [USER]. */
    enum class ChatTarget(val key: String, val label: String) {
        GROUP("group", "群聊"),
        USER("user", "私聊"),
        BOT("bot", "机器人"),
        ;

        companion object {
            fun parse(raw: String?): ChatTarget =
                entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: USER
        }
    }

    companion object {
        /** The internal link [uri] names, or null when it is not one of ours. */
        fun parse(uri: Uri?): AppLink? {
            if (uri == null) return null
            return when (uri.scheme?.lowercase()) {
                "xiaoman" -> {
                    val path = uri.path.orEmpty().trimEnd('/')
                    if (uri.host.equals("alarm", ignoreCase = true) && path == "/set") {
                        AlarmSet(time = uri.queryParameter("time"), label = uri.queryParameter("label"))
                    } else {
                        null
                    }
                }
                "yunhu" -> when (uri.host?.lowercase()) {
                    "chat-add" -> uri.queryParameter("id")?.let { ChatAdd(it, ChatTarget.parse(uri.queryParameter("type"))) }
                    "post-detail" -> uri.queryParameter("id")?.let(::PostDetail)
                    else -> null
                }
                else -> null
            }
        }

        private fun Uri.queryParameter(name: String): String? =
            getQueryParameter(name)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** Next epoch millis at [hhmm] ("HH:mm"): today when still ahead, else tomorrow; null when malformed. */
fun nextAlarmMillis(hhmm: String, now: Long = System.currentTimeMillis()): Long? {
    val match = Regex("""^\s*([01]?\d|2[0-3]):([0-5]\d)\s*$""").matchEntire(hhmm) ?: return null
    val calendar = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, match.groupValues[1].toInt())
        set(Calendar.MINUTE, match.groupValues[2].toInt())
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
    return calendar.timeInMillis
}

/** "HH:mm" one hour from [now], the default shown when a link carries no time. */
fun defaultAlarmTime(now: Long = System.currentTimeMillis()): String {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.HOUR_OF_DAY, 1)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(calendar.timeInMillis))
}
