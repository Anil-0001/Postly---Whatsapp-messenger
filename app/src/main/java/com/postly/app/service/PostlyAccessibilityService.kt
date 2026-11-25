package com.postly.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
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
import kotlinx.coroutines.*
import java.net.URLEncoder

enum class AutomationStep {
    IDLE,
    START,
    SEARCH_CLICKED,
    SEARCH_TYPED,
    CONTACT_CLICKED,
    CHAT_ENTERED,
    MESSAGE_TYPED,
    SEND_CLICKED,
    DONE
}

class PostlyAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var lastActionTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "PostlyAccessibilityService Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // Case 1: Contact Picker Mode
        if (isPickingContact && (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b")) {
            handleContactPickerEvent(event)
            return
        }

        // Case 2: Auto-unlock lockscreen if device is locked
        if (packageName == "com.android.systemui" && pendingTask != null) {
            val rootNode = rootInActiveWindow ?: return
            handleLockScreenUnlock(rootNode)
            return
        }

        // Case 3: Message Dispatch Automation (Deterministic State Machine)
        val current = pendingTask
        if (current != null && (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b")) {
            val rootNode = rootInActiveWindow ?: return
            runWhatsAppStateMachine(rootNode, current)
        }
    }

    /**
     * Contact Picker: Captures chat name when clicked inside WhatsApp
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
        Log.d(TAG, "Captured WhatsApp contact: $contactName")
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
     * Lockscreen Auto-Unlock
     */
    private fun handleLockScreenUnlock(rootNode: AccessibilityNodeInfo) {
        val prefs = getSharedPreferences("postly_prefs", Context.MODE_PRIVATE)
        val savedPin = prefs.getString("device_screen_pin", "") ?: ""
        if (savedPin.isEmpty() || isUnlocking) return

        val now = System.currentTimeMillis()
        if (now - lastActionTime < 1000) return
        lastActionTime = now

        isUnlocking = true
        serviceScope.launch {
            try {
                Log.d(TAG, "Handling lock screen unlock...")

                // Check if PIN buttons are visible
                var hasPinButtons = findPinpadDigit(rootNode, "1") != null
                var passwordField = findPasswordField(rootNode)

                // If not visible, swipe up to reveal keypad
                if (!hasPinButtons && passwordField == null) {
                    Log.d(TAG, "Swiping up on lockscreen to reveal PIN keypad...")
                    performSwipeUpGesture()
                    delay(800)
                }

                val currentRoot = rootInActiveWindow ?: rootNode
                passwordField = findPasswordField(currentRoot)

                if (passwordField != null) {
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, savedPin)
                    }
                    passwordField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    delay(300)
                    findAndClickNode(currentRoot, listOf("com.android.systemui:id/key_enter", "OK", "Done", "Enter"))
                } else {
                    for (digit in savedPin) {
                        val digitStr = digit.toString()
                        val keyNode = findPinpadDigit(currentRoot, digitStr)
                        if (keyNode != null) {
                            keyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            delay(150)
                        }
                    }
                    delay(300)
                    findAndClickNode(currentRoot, listOf("com.android.systemui:id/key_enter", "com.android.systemui:id/btn_enter", "OK", "Done", "Enter"))
                }

                delay(1200)
                val current = pendingTask
                if (current != null) {
                    launchWhatsAppTarget(current)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error unlocking lockscreen", e)
            } finally {
                isUnlocking = false
            }
        }
    }

    private fun performSwipeUpGesture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val swipePath = Path().apply {
                moveTo(540f, 2000f)
                lineTo(540f, 500f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 250))
                .build()
            dispatchGesture(gesture, null, null)
        }
    }

    /**
     * DETERMINISTIC STATE MACHINE:
     * Guarantees each action (Search, Type Name, Click Contact, Type Message, Click Send) happens EXACTLY ONCE!
     */
    private fun runWhatsAppStateMachine(rootNode: AccessibilityNodeInfo, task: ScheduleEntity) {
        val now = System.currentTimeMillis()

        when (currentStep) {
            AutomationStep.IDLE, AutomationStep.START -> {
                // Check if we are already in the chat conversation (e.g. opened via direct send intent)
                val chatEntry = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")?.firstOrNull()
                val sendBtn = findSendButton(rootNode)

                if (chatEntry != null || sendBtn != null) {
                    Log.d(TAG, "Already inside conversation screen. Moving to CHAT_ENTERED.")
                    currentStep = AutomationStep.CHAT_ENTERED
                    lastActionTime = now
                    return
                }

                // We are on WhatsApp Home screen: Click Search
                if (now - lastActionTime < 1000) return
                val searchBtn = findSearchButton(rootNode)
                if (searchBtn != null) {
                    Log.d(TAG, "Step 1: Clicking WhatsApp Search button")
                    searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    currentStep = AutomationStep.SEARCH_CLICKED
                    lastActionTime = now
                }
            }

            AutomationStep.SEARCH_CLICKED -> {
                // Wait at least 600ms for search input to be ready
                if (now - lastActionTime < 600) return

                val searchInput = findSearchInputNode(rootNode)
                if (searchInput != null) {
                    val targetName = task.recipientName.trim()
                    Log.d(TAG, "Step 2: Typing contact name '$targetName' into search bar")
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetName)
                    }
                    // ONLY use ACTION_SET_TEXT once (prevents duplicate typing!)
                    searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    currentStep = AutomationStep.SEARCH_TYPED
                    lastActionTime = now
                }
            }

            AutomationStep.SEARCH_TYPED -> {
                // Wait at least 1200ms for search results to appear
                if (now - lastActionTime < 1200) return

                val targetName = task.recipientName.trim()
                Log.d(TAG, "Step 3: Looking for search result matching '$targetName'")
                val contactNode = findFirstMatchingContactNode(rootNode, targetName)

                if (contactNode != null) {
                    var clickable: AccessibilityNodeInfo? = contactNode
                    while (clickable != null && !clickable.isClickable) {
                        clickable = clickable.parent
                    }
                    if (clickable != null) {
                        Log.d(TAG, "Step 4: Clicking matched contact in search results")
                        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        currentStep = AutomationStep.CONTACT_CLICKED
                        lastActionTime = now
                    }
                }
            }

            AutomationStep.CONTACT_CLICKED -> {
                // Wait for conversation window to open
                if (now - lastActionTime < 1000) return

                // Confirm chat entry field is visible (and not search bar)
                val chatEntry = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")?.firstOrNull()
                if (chatEntry != null) {
                    Log.d(TAG, "Step 5: Inside target chat conversation! Moving to CHAT_ENTERED.")
                    currentStep = AutomationStep.CHAT_ENTERED
                    lastActionTime = now
                }
            }

            AutomationStep.CHAT_ENTERED -> {
                if (now - lastActionTime < 400) return

                val chatEntry = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")?.firstOrNull()
                if (chatEntry != null) {
                    Log.d(TAG, "Step 6: Setting message text in conversation box: \"${task.message}\"")
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, task.message)
                    }
                    chatEntry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    currentStep = AutomationStep.MESSAGE_TYPED
                    lastActionTime = now
                }
            }

            AutomationStep.MESSAGE_TYPED -> {
                // Wait 400ms for send button to update with text
                if (now - lastActionTime < 400) return

                val sendBtn = findSendButton(rootNode)
                if (sendBtn != null) {
                    Log.d(TAG, "Step 7: Clicking WhatsApp SEND button!")
                    sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    currentStep = AutomationStep.SEND_CLICKED
                    lastActionTime = now

                    // Success! Complete schedule in database
                    serviceScope.launch(Dispatchers.IO) {
                        val db = (applicationContext as PostlyApplication).database
                        db.scheduleDao().markDone(task.id, System.currentTimeMillis())
                        AlarmScheduler.handleRecurringSchedule(applicationContext, task)

                        pendingTask = null
                        currentStep = AutomationStep.DONE

                        delay(1200)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
            }

            AutomationStep.SEND_CLICKED, AutomationStep.DONE -> {
                // Completed!
            }
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
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.className?.contains("EditText") == true) {
            return focused
        }

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
            // Ensure this is not the search input itself
            if (node.className?.contains("EditText") == true) continue
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
                currentStep = AutomationStep.START
                startActivity(intent)
                Log.d(TAG, "Launched WhatsApp. State initialized to START.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch WhatsApp", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
        currentStep = AutomationStep.IDLE
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

        var currentStep = AutomationStep.IDLE
        private var isUnlocking = false

        fun isServiceRunning(): Boolean = instance != null
    }
}
