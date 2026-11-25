package com.postly.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
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
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked && pendingTask != null) {
            val rootNode = rootInActiveWindow ?: return
            handleLockScreenUnlock(rootNode)
            return
        }

        // Case 3: Message Dispatch Automation (Strict State Machine)
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
     * Lockscreen Auto-Unlock for OnePlus (OxygenOS 14)
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
                Log.d(TAG, "Executing OnePlus lockscreen unlock...")

                val width = resources.displayMetrics.widthPixels
                val height = resources.displayMetrics.heightPixels

                // Check if PIN buttons or password field are already visible
                var hasPinButtons = findPinpadDigitNode(rootNode, "1") != null
                var passwordField = findPasswordField(rootNode)

                // If keypad not yet visible, perform smooth swipe-up gesture
                if (!hasPinButtons && passwordField == null) {
                    Log.d(TAG, "Swiping up on OnePlus lockscreen to reveal keypad...")
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
                    // Type each digit in saved PIN
                    for (digit in savedPin) {
                        val digitStr = digit.toString()
                        val digitNode = findPinpadDigitNode(currentRoot, digitStr)
                        var tapped = false

                        if (digitNode != null) {
                            val bounds = Rect()
                            digitNode.getBoundsInScreen(bounds)
                            if (bounds.centerX() > 0 && bounds.centerY() > 0) {
                                Log.d(TAG, "Tapping node bounds for digit $digitStr: (${bounds.centerX()}, ${bounds.centerY()})")
                                clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                                tapped = true
                            } else {
                                tapped = digitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }
                        }

                        // If accessibility node wasn't found or clickable (common on OxygenOS 14 keyguard security)
                        if (!tapped) {
                            val coords = getPinpadCoordinates(digit, width, height)
                            if (coords != null) {
                                Log.d(TAG, "Fallback tapping OnePlus PIN coordinate for '$digit' at (${coords.first}, ${coords.second})")
                                clickAt(coords.first, coords.second)
                            }
                        }
                        delay(220)
                    }

                    delay(350)
                    // Press Enter if required on device
                    val enterTapped = findAndClickNode(currentRoot, listOf("com.android.systemui:id/key_enter", "com.android.systemui:id/btn_enter", "OK", "Done", "Enter"))
                    if (!enterTapped) {
                        // Fallback enter coordinate for OnePlus keypad
                        val enterCoords = Pair(width * 0.75f, height * 0.83f)
                        clickAt(enterCoords.first, enterCoords.second)
                    }
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

    private fun getPinpadCoordinates(digit: Char, width: Int, height: Int): Pair<Float, Float>? {
        val col1 = width * 0.25f // 270 on 1080
        val col2 = width * 0.50f // 540 on 1080
        val col3 = width * 0.75f // 810 on 1080

        // OxygenOS 14 keypad layout on 1080x2412:
        val row1 = height * 0.58f // ~1400
        val row2 = height * 0.66f // ~1590
        val row3 = height * 0.745f // ~1795
        val row4 = height * 0.83f // ~2000

        return when (digit) {
            '1' -> Pair(col1, row1)
            '2' -> Pair(col2, row1)
            '3' -> Pair(col3, row1)
            '4' -> Pair(col1, row2)
            '5' -> Pair(col2, row2)
            '6' -> Pair(col3, row2)
            '7' -> Pair(col1, row3)
            '8' -> Pair(col2, row3)
            '9' -> Pair(col3, row3)
            '0' -> Pair(col2, row4)
            else -> null
        }
    }

    private fun performSwipeUpGesture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val swipePath = Path().apply {
                moveTo(540f, 2100f)
                lineTo(540f, 400f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 250))
                .build()
            dispatchGesture(gesture, null, null)
        }
    }

    /**
     * DETERMINISTIC STATE MACHINE FOR WHATSAPP AUTOMATION
     */
    private fun runWhatsAppStateMachine(rootNode: AccessibilityNodeInfo, task: ScheduleEntity) {
        val now = System.currentTimeMillis()

        when (currentStep) {
            AutomationStep.IDLE, AutomationStep.START -> {
                val chatEntry = findChatMessageEntryNode(rootNode)
                val sendBtn = findWhatsAppSendButton(rootNode)

                if (chatEntry != null || sendBtn != null) {
                    Log.d(TAG, "Already inside conversation screen. Moving to CHAT_ENTERED.")
                    currentStep = AutomationStep.CHAT_ENTERED
                    lastActionTime = now
                    return
                }

                // Click Search on WhatsApp Home screen
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
                if (now - lastActionTime < 600) return

                val searchInput = findSearchInputNode(rootNode)
                if (searchInput != null) {
                    val targetName = task.recipientName.trim()
                    Log.d(TAG, "Step 2: Typing contact name '$targetName' into search bar")
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetName)
                    }
                    // ONLY use ACTION_SET_TEXT once
                    searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    currentStep = AutomationStep.SEARCH_TYPED
                    lastActionTime = now
                }
            }

            AutomationStep.SEARCH_TYPED -> {
                if (now - lastActionTime < 1000) return

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
                if (now - lastActionTime < 800) return

                val chatEntry = findChatMessageEntryNode(rootNode)
                if (chatEntry != null) {
                    Log.d(TAG, "Step 5: Chat conversation opened! Moving to CHAT_ENTERED.")
                    currentStep = AutomationStep.CHAT_ENTERED
                    lastActionTime = now
                }
            }

            AutomationStep.CHAT_ENTERED -> {
                if (now - lastActionTime < 300) return

                val chatEntry = findChatMessageEntryNode(rootNode)
                if (chatEntry != null) {
                    currentStep = AutomationStep.MESSAGE_TYPED
                    lastActionTime = now

                    serviceScope.launch {
                        Log.d(TAG, "Step 6: Injecting message into WhatsApp chat entry (Single injection): \"${task.message}\"")
                        chatEntry.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        delay(120)

                        // 1. Single injection - ACTION_SET_TEXT
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, task.message)
                        }
                        val setTextSuccess = chatEntry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

                        // Fallback to clipboard ONLY if ACTION_SET_TEXT returned false
                        if (!setTextSuccess) {
                            Log.d(TAG, "ACTION_SET_TEXT returned false, using clipboard paste fallback")
                            copyToClipboard(task.message)
                            delay(100)
                            chatEntry.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        }

                        // 2. Wait for WhatsApp to render the green Send button (replaces mic icon)
                        delay(400)

                        // 3. Find and click Send button
                        val freshRoot = rootInActiveWindow ?: rootNode
                        val sendBtn = findWhatsAppSendButton(freshRoot)

                        var sendClicked = false
                        if (sendBtn != null) {
                            Log.d(TAG, "Step 7: Found WhatsApp Send button node! Clicking...")
                            val rect = Rect()
                            sendBtn.getBoundsInScreen(rect)
                            if (rect.centerX() > 0 && rect.centerY() > 0) {
                                Log.d(TAG, "Dispatching tap gesture at Send button coordinates: (${rect.centerX()}, ${rect.centerY()})")
                                clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                                sendClicked = true
                            }
                            if (!sendClicked) {
                                sendClicked = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }
                        }

                        // 4. Guaranteed coordinate fallback if node wasn't clickable or found
                        // In WhatsApp, the Send button is always at the bottom-right aligned with the message input box
                        if (!sendClicked) {
                            val entryRect = Rect()
                            chatEntry.getBoundsInScreen(entryRect)
                            val displayMetrics = resources.displayMetrics
                            val fallbackX = displayMetrics.widthPixels - 75f
                            val fallbackY = if (entryRect.centerY() > 0) entryRect.centerY().toFloat() else (displayMetrics.heightPixels - 120f)
                            Log.d(TAG, "Step 7 Fallback: Dispatching tap gesture at Send button coordinates: ($fallbackX, $fallbackY)")
                            clickAt(fallbackX, fallbackY)
                        }

                        currentStep = AutomationStep.SEND_CLICKED

                        // 5. Mark task DONE in database and re-arm if recurring
                        withContext(Dispatchers.IO) {
                            val db = (applicationContext as PostlyApplication).database
                            db.scheduleDao().markDone(task.id, System.currentTimeMillis())
                            AlarmScheduler.handleRecurringSchedule(applicationContext, task)
                        }

                        pendingTask = null
                        currentStep = AutomationStep.DONE
                        Log.d(TAG, "Step 8: WhatsApp message successfully sent! Task marked DONE.")

                        delay(1500)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
            }

            AutomationStep.MESSAGE_TYPED -> {
                // Handled directly inside coroutine above
            }

            AutomationStep.SEND_CLICKED, AutomationStep.DONE -> {
                // Completed!
            }
        }
    }

    /**
     * Recursively searches for WhatsApp Send button across all versions
     */
    private fun findWhatsAppSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidate = scanForSendButton(root)
        if (candidate != null) return candidate

        // Search by view IDs
        val ids = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/btn_send",
            "com.whatsapp:id/send_container"
        )
        for (id in ids) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (!list.isNullOrEmpty()) return list[0]
        }

        // Search by ContentDescription "Send" or "भेजें"
        val sendWords = listOf("Send", "send", "भेजें")
        for (word in sendWords) {
            val textList = root.findAccessibilityNodeInfosByText(word)
            for (node in textList) {
                if (node.isClickable) return node
            }
        }

        return null
    }

    private fun scanForSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""

        if (desc.equals("Send", ignoreCase = true) || desc.contains("Send", ignoreCase = true) || desc.contains("भेजें") || id.endsWith(":id/send") || id.endsWith(":id/send_container")) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = scanForSendButton(child)
            if (found != null) return found
        }
        return null
    }

    private fun findChatMessageEntryNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp.w4b:id/entry",
            "com.whatsapp:id/conversation_text_entry",
            "com.whatsapp:id/input"
        )
        for (id in ids) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (!list.isNullOrEmpty()) return list[0]
        }

        val messageNodes = root.findAccessibilityNodeInfosByText("Message")
        for (node in messageNodes) {
            if (node.className?.contains("EditText") == true) return node
        }

        return findFirstEditText(root)
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
            if (node.className?.contains("EditText") == true) continue
            if (nodeText.contains(targetName, ignoreCase = true)) {
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

    private fun findPinpadDigitNode(rootNode: AccessibilityNodeInfo, digit: String): AccessibilityNodeInfo? {
        // 1. By ID
        val idList = listOf(
            "com.android.systemui:id/key$digit",
            "com.android.systemui:id/button$digit",
            "com.android.systemui:id/digit_text"
        )
        for (id in idList) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.text?.toString() == digit || node.contentDescription?.toString() == digit) {
                    return node
                }
            }
        }

        // 2. By Text or Description
        val textNodes = rootNode.findAccessibilityNodeInfosByText(digit)
        for (node in textNodes) {
            if (node.text?.toString() == digit || node.contentDescription?.toString() == digit) {
                return node
            }
        }

        return null
    }

    private fun findAndClickNode(rootNode: AccessibilityNodeInfo, targets: List<String>): Boolean {
        for (target in targets) {
            val byId = rootNode.findAccessibilityNodeInfosByViewId(target)
            if (!byId.isNullOrEmpty()) {
                val node = byId[0]
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.centerX() > 0 && rect.centerY() > 0) {
                    clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                    return true
                }
            }

            val byText = rootNode.findAccessibilityNodeInfosByText(target)
            if (!byText.isNullOrEmpty()) {
                val node = byText[0]
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.centerX() > 0 && rect.centerY() > 0) {
                    clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                    return true
                }
            }
        }
        return false
    }

    private fun clickAt(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PostlyMessage", text))
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard copy error", e)
        }
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
