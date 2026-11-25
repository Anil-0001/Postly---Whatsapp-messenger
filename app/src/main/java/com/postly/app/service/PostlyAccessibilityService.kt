package com.postly.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.postly.app.MainActivity
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

        val packageName = event.packageName?.toString() ?: ""

        // Case 1: Contact Picking Mode
        if (isPickingContact && (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b")) {
            handleContactPickerEvent(event)
            return
        }

        // Case 2: Auto-unlock lockscreen if device is locked
        if (packageName == "com.android.systemui" && pendingTask != null) {
            val rootNode = rootInActiveWindow ?: return
            serviceScope.launch {
                handleLockScreenUnlock(rootNode)
            }
            return
        }

        // Case 3: Message Dispatch Automation
        val current = pendingTask
        if (current != null && (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b")) {
            serviceScope.launch {
                delay(600)
                val rootNode = rootInActiveWindow ?: return@launch
                handleWhatsAppAutomation(rootNode, current)
            }
        }
    }

    /**
     * Intercepts chat clicks in WhatsApp to pick contact/group for Postly
     */
    private fun handleContactPickerEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return

        val conversationHeaderIds = listOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name",
            "com.whatsapp:id/toolbar_title"
        )
        for (id in conversationHeaderIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty() && !nodes[0].text.isNullOrBlank()) {
                val contactName = nodes[0].text.toString().trim()
                completeContactPick(contactName)
                return
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val source = event.source
            if (source != null) {
                val rowContactIds = listOf(
                    "com.whatsapp:id/conversations_row_contact_name",
                    "com.whatsapp.w4b:id/conversations_row_contact_name"
                )
                for (id in rowContactIds) {
                    val nodes = source.findAccessibilityNodeInfosByViewId(id)
                    if (!nodes.isNullOrEmpty() && !nodes[0].text.isNullOrBlank()) {
                        val contactName = nodes[0].text.toString().trim()
                        completeContactPick(contactName)
                        return
                    }
                }

                val text = source.text?.toString() ?: source.contentDescription?.toString()
                if (!text.isNullOrBlank() && text.length > 1 && !text.equals("Search", ignoreCase = true)) {
                    completeContactPick(text.trim())
                    return
                }
            }
        }
    }

    private fun completeContactPick(contactName: String) {
        if (!isPickingContact) return
        Log.d(TAG, "Successfully captured WhatsApp contact: $contactName")
        isPickingContact = false
        pickedContactName = contactName

        onContactPickedCallback?.invoke(contactName)

        serviceScope.launch {
            delay(300)
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(200)
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra("picked_contact", contactName)
            }
            startActivity(intent)
        }
    }

    /**
     * Auto-unlock lockscreen
     */
    private suspend fun handleLockScreenUnlock(rootNode: AccessibilityNodeInfo) {
        val prefs = getSharedPreferences("postly_prefs", Context.MODE_PRIVATE)
        val savedPin = prefs.getString("device_screen_pin", "") ?: ""
        if (savedPin.isEmpty() || isUnlocking) return

        isUnlocking = true
        try {
            delay(400)
            Log.d(TAG, "Attempting lock screen unlock with PIN...")

            // Check if PIN entry or keypad is visible
            var passwordField = findPasswordField(rootNode)
            var hasDigits = findPinpadDigit(rootNode, "1") != null

            // If not visible yet, perform swipe up gesture to reveal PIN pad!
            if (passwordField == null && !hasDigits) {
                Log.d(TAG, "Lock screen needs swipe up to reveal keypad...")
                performSwipeUpGesture()
                delay(800)
            }

            val currentRoot = rootInActiveWindow ?: rootNode
            passwordField = findPasswordField(currentRoot)

            if (passwordField != null) {
                copyToClipboard(savedPin)
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, savedPin)
                }
                passwordField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                passwordField.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                delay(300)
                findAndClickNode(currentRoot, listOf("com.android.systemui:id/key_enter", "OK", "Done", "Enter"))
            } else {
                for (digit in savedPin) {
                    val digitStr = digit.toString()
                    val keyNode = findPinpadDigit(currentRoot, digitStr)
                    if (keyNode != null) {
                        keyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        delay(120)
                    }
                }
                delay(300)
                findAndClickNode(currentRoot, listOf("com.android.systemui:id/key_enter", "com.android.systemui:id/btn_enter", "OK", "Done", "Enter"))
            }

            delay(1000)
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

    private fun performSwipeUpGesture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val swipePath = Path().apply {
                moveTo(540f, 1900f)
                lineTo(540f, 400f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 300))
                .build()
            dispatchGesture(gesture, null, null)
        }
    }

    /**
     * WhatsApp Automation: Searches for target contact, inputs text, clicks Send
     */
    private suspend fun handleWhatsAppAutomation(initialRoot: AccessibilityNodeInfo, task: ScheduleEntity) {
        if (isProcessing) return
        isProcessing = true

        try {
            var currentRoot: AccessibilityNodeInfo = rootInActiveWindow ?: initialRoot

            // Step 1: Check if Send button is already visible (inside chat)
            val sendBtn = findSendButton(currentRoot)
            if (sendBtn != null) {
                fillAndSend(currentRoot, task.message, sendBtn, task)
                return
            }

            // Step 2: On WhatsApp Home Screen -> Search for target
            val targetName = task.recipientName.trim()
            if (targetName.isNotEmpty()) {
                Log.d(TAG, "Searching for contact in WhatsApp: $targetName")

                // Find and click search button
                val searchBtn = findSearchButton(currentRoot)
                if (searchBtn != null) {
                    searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(800)
                }

                currentRoot = rootInActiveWindow ?: currentRoot

                // Find the search input box (via focus, view IDs, or EditText class)
                val searchInput = findSearchInputNode(currentRoot)
                if (searchInput != null) {
                    Log.d(TAG, "Found WhatsApp search input field. Typing '$targetName'...")
                    copyToClipboard(targetName)

                    val textArgs = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetName)
                    }
                    searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArgs)
                    searchInput.performAction(AccessibilityNodeInfo.ACTION_PASTE)

                    delay(1500) // Wait for search results
                    currentRoot = rootInActiveWindow ?: currentRoot

                    // Find contact in search results
                    val contactNode = findFirstMatchingContactNode(currentRoot, targetName)
                    if (contactNode != null) {
                        Log.d(TAG, "Found contact node in search results! Clicking to open chat...")
                        var clickable = contactNode
                        while (clickable != null && !clickable.isClickable) {
                            clickable = clickable.parent
                        }
                        clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                        delay(1200) // Wait for chat window to open
                        currentRoot = rootInActiveWindow ?: currentRoot

                        val chatSendBtn = findSendButton(currentRoot)
                        val chatEntry = findEntryField(currentRoot)

                        if (chatEntry != null || chatSendBtn != null) {
                            fillAndSend(currentRoot, task.message, chatSendBtn, task)
                        }
                    } else {
                        Log.w(TAG, "Could not find contact '$targetName' in search results")
                    }
                } else {
                    Log.w(TAG, "Could not locate WhatsApp search input box")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing WhatsApp automation", e)
        } finally {
            isProcessing = false
        }
    }

    private suspend fun fillAndSend(root: AccessibilityNodeInfo, message: String, sendButton: AccessibilityNodeInfo?, task: ScheduleEntity) {
        val entry = findEntryField(root)
        if (entry != null) {
            copyToClipboard(message)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            }
            entry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            entry.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            delay(400)
        }

        // Re-find send button after typing
        val finalRoot = rootInActiveWindow ?: root
        val actualSendBtn = sendButton ?: findSendButton(finalRoot)

        if (actualSendBtn != null) {
            actualSendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "SUCCESS: WhatsApp Send button clicked automatically!")

            val db = (applicationContext as PostlyApplication).database
            db.scheduleDao().markDone(task.id, System.currentTimeMillis())

            AlarmScheduler.handleRecurringSchedule(applicationContext, task)
            pendingTask = null

            delay(1200)
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun findSearchButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/menuitem_search",
            "com.whatsapp.w4b:id/menuitem_search",
            "com.whatsapp:id/search_icon"
        )
        for (id in ids) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (!list.isNullOrEmpty() && list[0].isClickable) return list[0]
        }
        val textList = root.findAccessibilityNodeInfosByText("Search")
        for (node in textList) {
            if (node.isClickable) return node
        }
        return null
    }

    private fun findSearchInputNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. Check currently focused input
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.className?.contains("EditText") == true) {
            return focused
        }

        // 2. Check known view IDs
        val ids = listOf(
            "com.whatsapp:id/search_input",
            "com.whatsapp.w4b:id/search_input",
            "com.whatsapp:id/search_src_text",
            "com.whatsapp.w4b:id/search_src_text"
        )
        for (id in ids) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (!list.isNullOrEmpty()) return list[0]
        }

        // 3. Fallback: Recursively find any EditText
        return findFirstEditText(root)
    }

    private fun findFirstEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.contains("EditText") == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditText(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstMatchingContactNode(root: AccessibilityNodeInfo, targetName: String): AccessibilityNodeInfo? {
        val byText = root.findAccessibilityNodeInfosByText(targetName)
        for (node in byText) {
            val nodeText = node.text?.toString() ?: ""
            if (nodeText.contains(targetName, ignoreCase = true)) {
                return node
            }
        }
        return null
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
        return findFirstEditText(rootNode)
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

    fun launchWhatsAppTarget(task: ScheduleEntity) {
        try {
            val cleanPhone = task.recipientPhone.replace("+", "").replace(" ", "").replace("-", "")
            val targetPkg = if (task.platform.contains("business", ignoreCase = true)) "com.whatsapp.w4b" else "com.whatsapp"

            val intent = if (cleanPhone.isNotEmpty()) {
                val encodedMsg = URLEncoder.encode(task.message, "UTF-8")
                val uri = Uri.parse("whatsapp://send?phone=$cleanPhone&text=$encodedMsg")
                Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(targetPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            } else {
                packageManager.getLaunchIntentForPackage(targetPkg)?.apply {
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

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PostlyText", text))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
        isProcessing = false
        isUnlocking = false
        isPickingContact = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    companion object {
        private const val TAG = "PostlyAccessService"
        var instance: PostlyAccessibilityService? = null
        var pendingTask: ScheduleEntity? = null
        var isPickingContact = false
        var pickedContactName: String? = null
        var onContactPickedCallback: ((String) -> Unit)? = null

        private var isProcessing = false
        private var isUnlocking = false

        fun isServiceRunning(): Boolean = instance != null
    }
}
