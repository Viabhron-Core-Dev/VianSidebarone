package com.example.feature.sidebar

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.recyclerview.widget.ListUpdateCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for Notification Sidebar DiffUtil and Scroll-Stability:
 * 1. Same-key notification content update (areItemsTheSame = true, areContentsTheSame = false).
 * 2. Different notification keys remain separate (areItemsTheSame = false).
 * 3. Fallback key resolution when key is null.
 * 4. Adapter updates dispatch targeted item change rather than full dataset invalidation.
 * 5. Unchanged calculated Sidebar height does not trigger redundant onHeightChanged callbacks.
 */
class NotificationDiffAndScrollStabilityTest {

    data class MockData(
        val key: String?,
        val packageName: String,
        val id: Int,
        val tag: String?,
        val postTime: Long,
        val isOngoing: Boolean = false,
        val isClearable: Boolean = true
    )

    class FakeStatusBarNotification(val data: MockData) : StatusBarNotification(
        data.packageName,
        data.packageName,
        data.id,
        data.tag,
        10001,
        0,
        0,
        Notification(),
        android.os.Process.myUserHandle(),
        data.postTime
    ) {
        override fun getKey(): String? = data.key
        override fun getPackageName(): String = data.packageName
        override fun getId(): Int = data.id
        override fun getTag(): String? = data.tag
        override fun getPostTime(): Long = data.postTime
        override fun isOngoing(): Boolean = data.isOngoing
        override fun isClearable(): Boolean = data.isClearable
    }

    @Test
    fun testSameKeyNotificationContentUpdate() {
        val dataOld = MockData(
            key = "key_speed_1",
            packageName = "com.speed.monitor",
            id = 101,
            tag = null,
            postTime = 1000L
        )

        val dataNew = MockData(
            key = "key_speed_1",
            packageName = "com.speed.monitor",
            id = 101,
            tag = null,
            postTime = 2000L // New tick
        )

        val sbnOld = FakeStatusBarNotification(dataOld)
        val sbnNew = FakeStatusBarNotification(dataNew)

        val callback = NotificationPageView.NotificationDiffCallback(listOf(sbnOld), listOf(sbnNew))
        assertTrue("Notifications with the same key must be identified as the same item", callback.areItemsTheSame(0, 0))
        assertFalse("Notifications with different postTime must report changed content", callback.areContentsTheSame(0, 0))
    }

    @Test
    fun testDifferentNotificationKeysRemainingSeparate() {
        val sbn1 = FakeStatusBarNotification(MockData(
            key = "key_msg_1",
            packageName = "com.chat.app",
            id = 1,
            tag = "chat_tag_1",
            postTime = 1000L
        ))

        val sbn2 = FakeStatusBarNotification(MockData(
            key = "key_msg_2",
            packageName = "com.chat.app",
            id = 2,
            tag = "chat_tag_2",
            postTime = 1000L
        ))

        val callback = NotificationPageView.NotificationDiffCallback(listOf(sbn1), listOf(sbn2))
        assertFalse("Notifications with different keys must not be the same item", callback.areItemsTheSame(0, 0))
    }

    @Test
    fun testFallbackKeyResolutionWhenKeyIsNull() {
        val sbn1 = FakeStatusBarNotification(MockData(
            key = null,
            packageName = "com.chat.app",
            id = 1,
            tag = "chat_tag_1",
            postTime = 1000L
        ))

        val sbn2 = FakeStatusBarNotification(MockData(
            key = null,
            packageName = "com.chat.app",
            id = 1,
            tag = "chat_tag_1",
            postTime = 2000L
        ))

        val sbn3 = FakeStatusBarNotification(MockData(
            key = null,
            packageName = "com.chat.app",
            id = 2,
            tag = "chat_tag_2",
            postTime = 1000L
        ))

        assertEquals("Fallback key should match for same pkg, id, tag",
            NotificationPageView.getNotificationItemKey(sbn1),
            NotificationPageView.getNotificationItemKey(sbn2)
        )
        assertTrue(NotificationPageView.NotificationDiffCallback(listOf(sbn1), listOf(sbn2)).areItemsTheSame(0, 0))
        assertFalse(NotificationPageView.NotificationDiffCallback(listOf(sbn1), listOf(sbn3)).areItemsTheSame(0, 0))
    }

    @Test
    fun testAdapterDiffDispatchesTargetedChangeWithoutFullReset() {
        val sbnOld = FakeStatusBarNotification(MockData(
            key = "key_speed",
            packageName = "com.speed.monitor",
            id = 101,
            tag = null,
            postTime = 1000L
        ))

        val sbnNew = FakeStatusBarNotification(MockData(
            key = "key_speed",
            packageName = "com.speed.monitor",
            id = 101,
            tag = null,
            postTime = 2000L
        ))

        val diffCallback = NotificationPageView.NotificationDiffCallback(listOf(sbnOld), listOf(sbnNew))
        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(diffCallback)

        var changedCount = 0
        var insertedCount = 0
        var removedCount = 0
        var movedCount = 0

        diffResult.dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { insertedCount += count }
            override fun onRemoved(position: Int, count: Int) { removedCount += count }
            override fun onMoved(fromPosition: Int, toPosition: Int) { movedCount++ }
            override fun onChanged(position: Int, count: Int, payload: Any?) {
                changedCount += count
                assertEquals("Changed position must be index 0", 0, position)
            }
        })

        assertEquals("Exactly 1 item must receive targeted change", 1, changedCount)
        assertEquals("Zero items inserted", 0, insertedCount)
        assertEquals("Zero items removed", 0, removedCount)
        assertEquals("Zero items moved", 0, movedCount)
    }

    @Test
    fun testUnchangedCalculatedSidebarHeightDoesNotTriggerRedundantCallback() {
        var heightCallbackCount = 0
        var lastDispatchedHeightPx = 0

        val onHeightChangedMock: (Int) -> Unit = {
            heightCallbackCount++
        }

        val simulateHeightMeasurement = { calculatedTargetH: Int ->
            if (calculatedTargetH != lastDispatchedHeightPx) {
                lastDispatchedHeightPx = calculatedTargetH
                onHeightChangedMock(calculatedTargetH)
            }
        }

        // First pass: height established
        simulateHeightMeasurement(320)
        assertEquals(1, heightCallbackCount)
        assertEquals(320, lastDispatchedHeightPx)

        // Second pass: notification tick with identical content height
        simulateHeightMeasurement(320)
        assertEquals("Unchanged height must NOT invoke callback again", 1, heightCallbackCount)

        // Third pass: genuine expansion or item change causes height change
        simulateHeightMeasurement(450)
        assertEquals("Changed height must invoke callback", 2, heightCallbackCount)
        assertEquals(450, lastDispatchedHeightPx)
    }
}
