package io.github.qaisarpipeshift.utter.pulse

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qaisarpipeshift.utter.core.PulseIds
import io.github.qaisarpipeshift.utter.core.PulseRecords
import io.github.qaisarpipeshift.utter.core.PulseTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Extraction of the listener copy into `notif_posted` / `notif_removed` records (docs/spec.md §18.1), against
 * notifications the system builder really produced: texts, inbox lines, MessagingStyle messages, actions, the
 * image flag, the OTP-redaction marker and the reason-name map.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PulseListenerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private var counter = 0
    private val ids = PulseIds(device = "d", newId = { "id-${++counter}" }, clock = { 1_700_000_000_000 })

    private fun sbn(notification: Notification): StatusBarNotification {
        @Suppress("DEPRECATION")
        return StatusBarNotification(
            "com.example.chat", "com.example.chat", 7, null, 10_042, Process.myPid(), 0,
            notification, Process.myUserHandle(), 1_700_000_000_000,
        )
    }

    private fun builder(): Notification.Builder = Notification.Builder(context)
        .setContentTitle("Title")
        .setContentText("Text")
        .setCategory(Notification.CATEGORY_MESSAGE)
        .setChannelId("chats")
        .setGroup("friends")

    @Test
    fun `a plain notification extracts the standard fields`() {
        val record = PulseNotifs.posted(ids, sbn(builder().build()), ranking = null)

        assertEquals(PulseTypes.NOTIF_POSTED, record.getString("type"))
        assertEquals("com.example.chat", record.getString("pkg"))
        assertEquals(10_042, record.getInt("uid"))
        assertEquals(7, record.getInt("notifId"))
        assertEquals(1_700_000_000_000, record.getLong("at"))
        assertEquals("Title", record.getString("title"))
        assertEquals("Text", record.getString("text"))
        assertEquals(Notification.CATEGORY_MESSAGE, record.getString("category"))
        assertEquals("chats", record.getString("channelId"))
        assertEquals("friends", record.getString("group"))
        assertFalse(record.getBoolean("ongoing"))
        assertFalse(record.getBoolean("image"))
        assertFalse(record.getBoolean("redacted"))
        assertTrue(record.isNull("conversationTitle"))
        assertEquals(0, record.getJSONArray("messages").length())
        assertFalse(record.has("snapshot"))
        assertFalse(record.has("importance")) // no ranking, no rank fields
    }

    @Test
    fun `big text, sub text and progress come from the extras`() {
        val notification = builder()
            .setStyle(Notification.BigTextStyle().bigText("The long version"))
            .setSubText("2 chats")
            .setProgress(10, 3, false)
            .build()

        val record = PulseNotifs.posted(ids, sbn(notification), ranking = null)

        assertEquals("The long version", record.getString("bigText"))
        assertEquals("2 chats", record.getString("subText"))
        assertEquals(3, record.getInt("progress"))
        assertEquals(10, record.getInt("progressMax"))
    }

    @Test
    fun `inbox lines land as an array`() {
        val notification = builder()
            .setStyle(Notification.InboxStyle().addLine("one").addLine("two"))
            .build()

        val record = PulseNotifs.posted(ids, sbn(notification), ranking = null)

        assertEquals(listOf("one", "two"), record.getJSONArray("inbox").let { array ->
            (0 until array.length()).map { array.getString(it) }
        })
    }

    @Test
    fun `messaging style keeps sender, text and time per message`() {
        val person = android.app.Person.Builder()
            .setName("Ada")
            .setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(smallBitmap()).toIcon(context))
            .build()
        val style = Notification.MessagingStyle(person)
            .setConversationTitle("Team chat")
            .addMessage("hello", 1_700_000_100_000, person)
            .addMessage("hi", 1_700_000_200_000, "Bob")
        val notification = builder().setStyle(style).build()

        val record = PulseNotifs.posted(ids, sbn(notification), ranking = null)

        assertEquals("Team chat", record.getString("conversationTitle"))
        val messages = record.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("Ada", messages.getJSONObject(0).getString("sender"))
        assertEquals("hello", messages.getJSONObject(0).getString("text"))
        assertEquals(1_700_000_100_000, messages.getJSONObject(0).getLong("time"))
        assertEquals("Bob", messages.getJSONObject(1).getString("sender"))
    }

    @Test
    fun `actions are kept as titles and a large icon is only a flag`() {
        val intent = PendingIntent.getActivity(context, 0, Intent("x"), PendingIntent.FLAG_IMMUTABLE)
        val action = Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Reply", intent).build()
        val notification = builder()
            .setLargeIcon(smallBitmap())
            .addAction(action)
            .build()

        val record = PulseNotifs.posted(ids, sbn(notification), ranking = null)

        assertTrue(record.getBoolean("image"))
        assertEquals(listOf("Reply"), record.getJSONArray("actions").let { array ->
            (0 until array.length()).map { array.getString(it) }
        })
    }

    @Test
    fun `the system's otp-redacted copy is kept as received and marked`() {
        val notification = builder()
            .setContentTitle("Utter")
            .setContentText(PulseRecords.REDACTED_TEXT)
            .build()

        val record = PulseNotifs.posted(ids, sbn(notification), ranking = null)

        assertTrue(record.getBoolean("redacted"))
        assertEquals(PulseRecords.REDACTED_TEXT, record.getString("text"))
    }

    @Test
    fun `snapshot postings carry the flag`() {
        val record = PulseNotifs.posted(ids, sbn(builder().build()), ranking = null, snapshot = true)

        assertTrue(record.getBoolean("snapshot"))
    }

    @Test
    fun `a removal keeps the reason code and its name`() {
        val record = PulseNotifs.removed(ids, sbn(builder().build()), NotificationListenerService.REASON_CLICK)

        assertEquals(PulseTypes.NOTIF_REMOVED, record.getString("type"))
        assertEquals(NotificationListenerService.REASON_CLICK, record.getInt("reason"))
        assertEquals("REASON_CLICK", record.getString("reasonName"))
        assertEquals("REASON_CLICK", PulseNotifs.reasonName(NotificationListenerService.REASON_CLICK))
        assertTrue(PulseNotifs.reasonName(999).startsWith("reason_"))
    }

    @Test
    fun `the reason map covers the documented removal reasons`() {
        val names = listOf(
            "REASON_CLICK", "REASON_CANCEL", "REASON_CANCEL_ALL", "REASON_ERROR", "REASON_APP_CANCEL",
            "REASON_TIMEOUT", "REASON_SNOOZED", "REASON_CHANNEL_BANNED", "REASON_GROUP_SUMMARY_CANCELED",
        )
        for (name in names) {
            assertTrue("$name missing", PulseNotifs.reasonMap().values.contains(name))
        }
        assertTrue(PulseNotifs.reasonMap().size >= 20) // Android 15 documents 23 codes; the map grows with the OS
    }

    @Test
    fun `ranking fields are recorded when the ranking map provides them`() {
        val ranking = NotificationListenerService.Ranking()
        val record = PulseNotifs.posted(ids, sbn(builder().build()), ranking = ranking)

        assertTrue(record.has("importance"))
        assertTrue(record.has("rank"))
        assertTrue(record.has("dndSuppressed"))
    }

    @Test
    fun `the listener stays strictly read-only (spec §18-5)`() {
        // No action methods may be overridden or called: it never cancels, snoozes, ranks or unbinds.
        val actions = listOf(
            "cancelNotification", "cancelNotifications", "snoozeNotification",
            "requestRebind", "requestUnbind", "setInterruptionFilter",
        )
        val declared = PulseListener::class.java.declaredMethods.map { it.name }
        for (action in actions) {
            assertFalse("$action must not appear on the listener", declared.contains(action))
        }
    }

    private fun smallBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        return bitmap
    }
}
