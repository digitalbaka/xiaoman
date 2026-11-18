package dev.xiaoman.app

import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLinkTest {
    private fun parse(url: String) = AppLink.parse(Uri.parse(url))

    @Test
    fun parsesAlarmSet() {
        val link = assertIs<AppLink.AlarmSet>(parse("xiaoman://alarm/set?time=07%3A30&label=%E8%B5%B7%E5%BA%8A"))
        assertEquals("07:30", link.time)
        assertEquals("起床", link.label)
    }

    @Test
    fun parsesChatAddForEveryType() {
        assertEquals(AppLink.ChatTarget.GROUP, assertIs<AppLink.ChatAdd>(parse("yunhu://chat-add?id=442186744&type=group")).type)
        assertEquals(AppLink.ChatTarget.USER, assertIs<AppLink.ChatAdd>(parse("yunhu://chat-add?id=9127677&type=user")).type)
        assertEquals(AppLink.ChatTarget.BOT, assertIs<AppLink.ChatAdd>(parse("yunhu://chat-add?id=91276778&type=bot")).type)
        // An unknown type falls back to a private chat; the id is still used.
        val missingType = assertIs<AppLink.ChatAdd>(parse("yunhu://chat-add?id=42"))
        assertEquals("42", missingType.id)
        assertEquals(AppLink.ChatTarget.USER, missingType.type)
    }

    @Test
    fun parsesPostDetail() {
        assertEquals("863", assertIs<AppLink.PostDetail>(parse("yunhu://post-detail?id=863")).id)
    }

    @Test
    fun rejectsLinksWithoutIdOrForeignRoutes() {
        assertNull(parse("yunhu://chat-add?type=group"))
        assertNull(parse("yunhu://post-detail"))
        assertNull(parse("yunhu://unknown?id=1"))
        assertNull(parse("xiaoman://alarm/other"))
        assertNull(parse("https://example.com/a"))
    }

    @Test
    fun computesTheNextOccurrenceOfATime() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 1, 6, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val at = assertNotNull(nextAlarmMillis("07:30", now))
        val atCalendar = Calendar.getInstance().apply { timeInMillis = at }
        assertEquals(7, atCalendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, atCalendar.get(Calendar.MINUTE))

        // Exactly now is in the past for alarm purposes: schedule tomorrow.
        val tomorrow = assertNotNull(nextAlarmMillis("06:00", now))
        val delta = tomorrow - now
        assertTrue(delta >= 20 * 60 * 60 * 1000L && delta <= 25 * 60 * 60 * 1000L)
    }

    @Test
    fun rejectsMalformedTimes() {
        assertNull(nextAlarmMillis("7:3"))
        assertNull(nextAlarmMillis("25:00"))
        assertNull(nextAlarmMillis("noon"))
        assertNotNull(nextAlarmMillis(defaultAlarmTime()))
    }
}
