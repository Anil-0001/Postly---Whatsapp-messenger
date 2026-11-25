package com.postly.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import java.net.URLEncoder

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

        // 1. Check if device is on LockScreen (com.android.systemui) and user provided a PIN
        if (packageName == "com.android.systemui") {
            val rootNode = rootInActiveWindow ?: return
            serviceScope.launch {
                handleLockScreenUnlock(rootNode)
            }
            return
        }

        // 2. Check if WhatsApp or WhatsApp Business is in foreground
        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") {
            val rootNode = rootInActiveWindow ?: return
            serviceScope.launch {
                handleWhatsAppAutomation(rootNode, current)
            }
        }
    }

    /**
     * Auto-unlock lockscreen if user entered PIN in Postly Settings
     */
    private suspend fun handleLockScreenUnlock(rootNode: AccessibilityNodeInfo) {
        val prefs = getSharedPreferences("postly_prefs", Context.MODE_PRIVATE)
        val savedPin = prefs.getString("device_screen_pin", "") ?: ""
        if (savedPin.isEmpty() || isUnlocking) return

        isUnlocking = true
        try {
            delay(500)
            Log.d(TAG, "Attempting to auto-unlock device with saved PIN...")

            // Try to find password/PIN edit field first
            val passwordField = findPasswordField(rootNode)
            if (passwordField != null) {
                val arguments = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        savedPin
                    )
                }
                passwordField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                delay(300)
                // Press Enter / Confirm
                findAndClickNode(rootNode, listOf("com.android.systemui:id/key_enter", "com.android.systemui:id/btn_enter", "OK", "Done"))
            } else {
                // Pinpad digits 0-9
                for (digit in savedPin) {
                    val digitStr = digit.toString()
                    val keyNode = findPinpadDigit(rootNode, digitStr)
                    if (keyNode != null) {
                        keyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        delay(150)
                    }
                }
                delay(300)
                // Press Enter if required
                findAndClickNode(rootNode, listOf("com.android.systemui:id/key_enter", "com.android.systemui:id/btn_enter", "OK", "Done", "Enter"))
            }

            delay(1000)
            // Once unlocked, launch WhatsApp directly for the pending task
            val current = pendingTask
            if (current != null) {
                launchWhatsAppTarget(current)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during lock screen unlock", e)
        } finally {
            isUnlocking = false
        }
    }

    private fun findPasswordField(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val list = rootNode.findAccessibilityNodeInfosByViewId("com.android.systemui:id/passwordEntry")
        if (!list.isNullOrEmpty()) return list[0]

        val pinEntryList = rootNode.findAccessibilityNodeInfosByViewId("com.android.systemui:id/pinEntry")
        if (!pinEntryList.isNullOrEmpty()) return pinEntryList[0]

        return null
    }

    private fun findPinpadDigit(rootNode: AccessibilityNodeInfo, digit: String): AccessibilityNodeInfo? {
        val idList = listOf(
            "com.android.systemui:id/key$digit",
            "com.android.systemui:id/button$digit"
        )
        for (id in idList) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) return nodes[0]
        }

        // Search by text
        val textNodes = rootNode.findAccessibilityNodeInfosByText(digit)
        for (node in textNodes) {
            if (node.isClickable && node.text?.toString() == digit) {
                return node
            }
        }
        return null
    }

    private fun findAndClickNode(rootNode: AccessibilityNodeInfo, targets: List<String>): Boolean {
        for (target in targets) {
            val byId = rootNode.findAccessibilityNodeInfosByViewId(target)
            if (!byId.isNullOrEmpty() && byId[0].isClickable) {
                byId[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            val byText = rootNode.findAccessibilityNodeInfosByText(target)
            if (!byText.isNullOrEmpty() && byText[0].isClickable) {
                byText[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    /**
     * Target WhatsApp chat opening and message sending
     */
    private suspend fun handleWhatsAppAutomation(rootNode: AccessibilityNodeInfo, task: ScheduleEntity) {
        if (isProcessing) return
        isProcessing = true

        try {
            // STEP 1: Direct Send Button (If chat is already open)
            val sendButton = findSendButton(rootNode)
            if (sendButton != null) {
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
                Log.d(TAG, "Target reached: WhatsApp message sent successfully!")

                // Mark Task Done in Room Database
                val db = (applicationContext as PostlyApplication).database
                db.scheduleDao().markDone(task.id, System.currentTimeMillis())

                // Handle recurring rules
                AlarmScheduler.handleRecurringSchedule(applicationContext, task)

                pendingTask = null
                delay(1200)

                // Return to home screen or Postly
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }

            // STEP 2: If we are on WhatsApp Main/Home screen and contact name is specified without phone
            val targetName = task.recipientName.trim()
            if (targetName.isNotEmpty() && task.recipientPhone.isBlank()) {
                // Click Search icon
                val searchButton = findNodeByViewIdOrDesc(rootNode, listOf(
                    "com.whatsapp:id/menuitem_search",
                    "com.whatsapp.w4b:id/menuitem_search"
                ), "Search")

                if (searchButton != null && searchButton.isClickable) {
                    searchButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(500)
                }

                // Find search input and type recipient name
                val searchInput = findSearchBox(rootNode)
                if (searchInput != null) {
                    val arguments = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            targetName
                        )
                    }
                    searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    delay(1200)

                    // Click first matching contact from results
                    val contactNode = findNodeByText(rootNode, targetName)
                    if (contactNode != null) {
                        contactNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        delay(600)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing WhatsApp automation", e)
        } finally {
            isProcessing = false
        }
    }

    fun launchWhatsAppTarget(task: ScheduleEntity) {
        try {
            val cleanPhone = task.recipientPhone.replace("+", "").replace(" ", "").replace("-", "")
            val intent = if (cleanPhone.isNotEmpty()) {
                val encodedMsg = URLEncoder.encode(task.message, "UTF-8")
                val uri = Uri.parse("whatsapp://send?phone=$cleanPhone&text=$encodedMsg")
                Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(if (task.platform.contains("business", ignoreCase = true)) "com.whatsapp.w4b" else "com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            } else {
                packageManager.getLaunchIntentForPackage("com.whatsapp")?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            }

            if (intent != null) {
                startActivity(intent)
                Log.d(TAG, "Direct targeted WhatsApp intent fired!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch targeted WhatsApp", e)
        }
    }

    private fun findSendButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val knownSendIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/btn_send"
        )
        for (id in knownSendIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) return nodes[0]
        }

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
            if (!nodes.isNullOrEmpty()) return nodes[0]
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
            if (!nodes.isNullOrEmpty()) return nodes[0]
        }
        return null
    }

    private fun findNodeByViewIdOrDesc(rootNode: AccessibilityNodeInfo, ids: List<String>, desc: String): AccessibilityNodeInfo? {
        for (id in ids) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) return nodes[0]
        }
        val textNodes = rootNode.findAccessibilityNodeInfosByText(desc)
        return textNodes?.firstOrNull { it.isClickable }
    }

    private fun findNodeByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        return nodes?.firstOrNull { it.isClickable }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
        isProcessing = false
        isUnlocking = false
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
        private var isUnlocking = false

        fun isServiceRunning(): Boolean = instance != null
    }
}
