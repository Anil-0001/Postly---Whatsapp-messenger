package com.postly.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.postly.app.PostlyApplication
import com.postly.app.data.model.ScheduleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PostlyAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "PostlyAccessibilityService Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val current = pendingTask ?: return

        val packageName = event.packageName?.toString() ?: ""
        if (packageName != "com.whatsapp" && packageName != "com.whatsapp.w4b") return

        val rootNode = rootInActiveWindow ?: return

        serviceScope.launch {
            handleWhatsAppAutomation(rootNode, current)
        }
    }

    private suspend fun handleWhatsAppAutomation(rootNode: AccessibilityNodeInfo, task: ScheduleEntity) {
        if (isProcessing) return
        isProcessing = true

        try {
            // Step 1: Check if send button is already visible and enabled (e.g. opened via direct send intent)
            val sendButton = findSendButton(rootNode)
            if (sendButton != null) {
                // Ensure text is populated in the entry field if not already done
                val entryField = findEntryField(rootNode)
                if (entryField != null && (entryField.text.isNullOrEmpty() || entryField.text.toString().trim() != task.message.trim())) {
                    val arguments = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            task.message
                        )
                    }
                    entryField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    delay(300)
                }

                // Click Send Button
                sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Successfully clicked WhatsApp Send button!")

                // Mark Task Done in Room Database
                val db = (applicationContext as PostlyApplication).database
                db.scheduleDao().markDone(task.id, System.currentTimeMillis())

                // Reschedule if recurring
                AlarmScheduler.handleRecurringSchedule(applicationContext, task)

                pendingTask = null
                delay(1200)

                // Return to home screen or Postly
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }

            // Step 2: If we are in contact selection/search screen
            val searchInput = findSearchBox(rootNode)
            if (searchInput != null && !task.recipientName.isEmpty()) {
                val arguments = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        task.recipientName
                    )
                }
                searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                delay(1000)

                // Find and click contact result
                val contactNode = findNodeByText(rootNode, task.recipientName)
                contactNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing automation", e)
        } finally {
            isProcessing = false
        }
    }

    private fun findSendButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // WhatsApp Send Button resource IDs across different versions
        val knownSendIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/btn_send"
        )
        for (id in knownSendIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                return nodes[0]
            }
        }

        // Fallback by ContentDescription
        val descNodes = rootNode.findAccessibilityNodeInfosByText("Send")
        for (node in descNodes) {
            val desc = node.contentDescription?.toString()
            if (desc.equals("Send", ignoreCase = true) && node.isClickable) {
                return node
            }
        }
        return null
    }

    private fun findEntryField(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val knownEntryIds = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp.w4b:id/entry"
        )
        for (id in knownEntryIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                return nodes[0]
            }
        }
        return null
    }

    private fun findSearchBox(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val searchIds = listOf(
            "com.whatsapp:id/search_src_text",
            "com.whatsapp.w4b:id/search_src_text"
        )
        for (id in searchIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                return nodes[0]
            }
        }
        return null
    }

    private fun findNodeByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        return nodes?.firstOrNull { it.isClickable }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
        isProcessing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    companion object {
        private const val TAG = "PostlyAccessService"
        var instance: PostlyAccessibilityService? = null
        var pendingTask: ScheduleEntity? = null
        private var isProcessing = false

        fun isServiceRunning(): Boolean = instance != null
    }
}
