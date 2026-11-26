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
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
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
    private var isExecutingAutomation = false

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

        // Case 3: Message Dispatch Automation (Sequential Coroutine)
        val current = pendingTask
        if (current != null && (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b")) {
            if (!isExecutingAutomation) {
                if (!current.attachmentUri.isNullOrBlank()) {
                    startWhatsAppMediaAutomation(current)
                } else {
                    startWhatsAppAutomation(current)
                }
            }
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

                val (width, height) = getRealScreenSize(this@PostlyAccessibilityService)

                // Check if PIN buttons or password field are already visible
                var hasPinButtons = findPinpadDigitNode(rootNode, "1") != null
                var passwordField = findPasswordField(rootNode)

                // If keypad not yet visible, perform smooth swipe-up gesture
                if (!hasPinButtons && passwordField == null) {
                    Log.d(TAG, "Swiping up on OnePlus lockscreen to reveal keypad...")
                    performSwipeUpGesture()
                    delay(1000)
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
                    // Type each digit in saved PIN with high precision
                    for (digit in savedPin) {
                        val digitStr = digit.toString()
                        val freshRoot = rootInActiveWindow ?: currentRoot
                        val digitNode = findPinpadDigitNode(freshRoot, digitStr)
                        var tapped = false

                        if (digitNode != null) {
                            val bounds = Rect()
                            digitNode.getBoundsInScreen(bounds)
                            if (bounds.width() in 80..450 && bounds.height() in 80..450 && bounds.centerY() > 1100) {
                                Log.d(TAG, "Tapping node bounds for digit $digitStr: (${bounds.centerX()}, ${bounds.centerY()})")
                                clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                                tapped = true
                            }
                        }

                        // Fallback: Tap calibrated OnePlus Keypad coordinates
                        if (!tapped) {
                            val coords = getPinpadCoordinates(digit, width, height)
                            if (coords != null) {
                                Log.d(TAG, "Fallback tapping OnePlus PIN coordinate for '$digit' at (${coords.first}, ${coords.second})")
                                clickAt(coords.first, coords.second)
                            }
                        }
                        delay(350)
                    }

                    // On OnePlus OxygenOS 14, 4-digit PIN auto-submits when 4th digit is tapped
                    // For devices requiring OK or enter, tap Enter if still locked
                    delay(600)
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (km.isKeyguardLocked) {
                        Log.d(TAG, "Keyguard still locked after digits, attempting Enter/OK tap...")
                        val afterRoot = rootInActiveWindow
                        val enterClicked = afterRoot != null && findAndClickNode(afterRoot, listOf("com.android.systemui:id/key_enter", "OK", "Done", "Enter", "✓"))
                        if (!enterClicked) {
                            val enterCoords = getPinpadCoordinates('E', width, height)
                            if (enterCoords != null) {
                                clickAt(enterCoords.first, enterCoords.second)
                            }
                        }
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

    private fun getRealScreenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                return Pair(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay
                val realMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(realMetrics)
                return Pair(realMetrics.widthPixels, realMetrics.heightPixels)
            }
        }
        return Pair(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
    }

    private fun getPinpadCoordinates(digit: Char, width: Int, height: Int): Pair<Float, Float>? {
        val col1: Float
        val col2: Float
        val col3: Float
        val row1: Float
        val row2: Float
        val row3: Float
        val row4: Float

        if (width == 1080) {
            // Calibrated OnePlus Nord CE 2 Lite (OxygenOS 14) Keypad Layout on 1080x2412
            col1 = 270f
            col2 = 540f // center on 1080
            col3 = 810f

            row1 = 1380f
            row2 = 1580f
            row3 = 1780f
            row4 = 1980f
        } else {
            // Scaled relative to the 1080x2412 baseline for other screen resolutions
            val scaleX = width / 1080f
            val scaleY = height / 2412f

            col1 = 270f * scaleX
            col2 = 540f * scaleX
            col3 = 810f * scaleX

            row1 = 1380f * scaleY
            row2 = 1580f * scaleY
            row3 = 1780f * scaleY
            row4 = 1980f * scaleY
        }

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
            'E', 'e' -> Pair(col3, row4)
            else -> null
        }
    }

    private fun performSwipeUpGesture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val (realWidth, realHeight) = getRealScreenSize(this)
            val startX = if (realWidth > 0) realWidth / 2f else 540f
            val startY = if (realHeight > 0) realHeight * 0.88f else 2100f
            val endY = if (realHeight > 0) realHeight * 0.15f else 400f

            val swipePath = Path().apply {
                moveTo(startX, startY)
                lineTo(startX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 250))
                .build()
            dispatchGesture(gesture, null, null)
        }
    }

    /**
     * SEQUENTIAL WHATSAPP AUTOMATION
     * Eliminates state race conditions and ensures steps execute strictly in order:
     * 1. Search & select exact contact (if phone is empty)
     * 2. Find chat entry and inject message text
     * 3. Click green Send button
     * 4. Mark task DONE in database
     */
    private fun startWhatsAppAutomation(task: ScheduleEntity) {
        if (isExecutingAutomation) return
        isExecutingAutomation = true

        serviceScope.launch {
            try {
                Log.d(TAG, "Starting sequential WhatsApp automation for: ${task.recipientName}")

                // 1. Wait for WhatsApp to settle
                delay(1000)

                val cleanPhone = task.recipientPhone.replace("+", "").replace(" ", "").replace("-", "")
                val needsSearch = cleanPhone.isEmpty() && task.recipientName.isNotEmpty()

                if (needsSearch) {
                    val opened = searchAndOpenContact(task.recipientName)
                    if (!opened) {
                        Log.e(TAG, "Failed to find and open contact: ${task.recipientName}")
                        RetryManager.handleFailure(applicationContext, task, "Contact '${task.recipientName}' not found in WhatsApp")
                        pendingTask = null
                        isExecutingAutomation = false
                        return@launch
                    }
                }

                // 2. We are now inside the chat conversation!
                // Wait for the message entry box to appear (poll up to 10 times)
                var chatEntry: AccessibilityNodeInfo? = null
                for (attempt in 1..10) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        chatEntry = findChatMessageEntryNode(root)
                        if (chatEntry != null) break
                    }
                    delay(300)
                }

                if (chatEntry == null) {
                    Log.e(TAG, "Could not find chat message entry box in conversation")
                    RetryManager.handleFailure(applicationContext, task, "Chat conversation entry box not found")
                    pendingTask = null
                    isExecutingAutomation = false
                    return@launch
                }

                // 3. Inject message text (Single injection)
                Log.d(TAG, "Found chat entry. Typing message: ${task.message}")
                chatEntry.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                delay(150)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, task.message)
                }
                val setTextSuccess = chatEntry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!setTextSuccess) {
                    copyToClipboard(task.message)
                    delay(100)
                    chatEntry.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }

                // 4. Wait for WhatsApp to render the green Send button
                delay(450)

                // 5. Find and click Send button (retry up to 5 times)
                var sendClicked = false
                for (attempt in 1..5) {
                    val freshRoot = rootInActiveWindow
                    if (freshRoot != null) {
                        val sendBtn = findWhatsAppSendButton(freshRoot)
                        if (sendBtn != null) {
                            Log.d(TAG, "Found WhatsApp Send button! Clicking (attempt $attempt)...")
                            val rect = Rect()
                            sendBtn.getBoundsInScreen(rect)

                            sendClicked = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (!sendClicked && rect.centerX() > 0) {
                                clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                                sendClicked = true
                            }
                            if (sendClicked) break
                        }
                    }
                    delay(250)
                }

                // 6. Coordinate fallback if node wasn't clicked
                if (!sendClicked) {
                    val freshRoot = rootInActiveWindow
                    val freshEntry = if (freshRoot != null) findChatMessageEntryNode(freshRoot) else null
                    val entryRect = Rect()
                    freshEntry?.getBoundsInScreen(entryRect)
                    val fallbackX = resources.displayMetrics.widthPixels - 85f
                    val fallbackY = if (entryRect.centerY() > 0) entryRect.centerY().toFloat() else 2310f
                    Log.d(TAG, "Fallback clicking Send button at ($fallbackX, $fallbackY)")
                    clickAt(fallbackX, fallbackY)
                }

                // 7. Mark task DONE in database and re-arm if recurring
                withContext(Dispatchers.IO) {
                    val db = (applicationContext as PostlyApplication).database
                    db.scheduleDao().markDone(task.id, System.currentTimeMillis())
                    AlarmScheduler.handleRecurringSchedule(applicationContext, task)
                }

                pendingTask = null
                Log.d(TAG, "Step 8: WhatsApp message successfully sent! Task marked DONE.")

                delay(1500)
                performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (e: Exception) {
                Log.e(TAG, "Error in WhatsApp automation", e)
                RetryManager.handleFailure(applicationContext, task, "Automation error: ${e.message ?: "Unknown"}")
                pendingTask = null
            } finally {
                isExecutingAutomation = false
            }
        }
    }

    /**
     * MEDIA AUTOMATION FOR WHATSAPP
     * Handles images, videos, audio, and documents:
     * 1. Detects whether WhatsApp opened directly into Media Preview or into Contact Picker / Forward screen
     * 2. If Contact Picker: searches recipient, selects contact, and clicks green FAB forward arrow
     * 3. On Media Preview: ensures caption is present and clicks green Send FAB button
     * 4. Marks schedule as DONE and returns cleanly
     */
    private fun startWhatsAppMediaAutomation(task: ScheduleEntity) {
        if (isExecutingAutomation) return
        isExecutingAutomation = true

        serviceScope.launch {
            try {
                Log.d(TAG, "Starting WhatsApp Media Automation for attachment: ${task.attachmentUri}")
                delay(1200) // Allow WhatsApp UI to transition and render

                val (realWidth, realHeight) = getRealScreenSize(this@PostlyAccessibilityService)
                val widthF = if (realWidth > 0) realWidth.toFloat() else 1080f
                val heightF = if (realHeight > 0) realHeight.toFloat() else 2412f
                val cleanPhone = task.recipientPhone.replace("+", "").replace(" ", "").replace("-", "")

                // Step 1: Check if WhatsApp opened Contact Picker (e.g. cleanPhone empty or forward list)
                val initialRoot = rootInActiveWindow
                val isContactPicker = initialRoot != null && (
                    findSearchButton(initialRoot) != null ||
                    !initialRoot.findAccessibilityNodeInfosByViewId("com.whatsapp:id/contactpicker_row_name").isNullOrEmpty() ||
                    !initialRoot.findAccessibilityNodeInfosByViewId("com.whatsapp:id/search_src_text").isNullOrEmpty()
                )

                if (isContactPicker && task.recipientName.isNotEmpty()) {
                    Log.d(TAG, "Media dispatch: Contact picker detected, searching for ${task.recipientName}...")
                    val contactSelected = searchAndSelectShareContact(task.recipientName)
                    if (contactSelected) {
                        delay(600)
                        // Click the green FAB button to proceed to Media Preview
                        var fabClicked = false
                        for (attempt in 1..5) {
                            val freshRoot = rootInActiveWindow
                            if (freshRoot != null) {
                                val fab = findMediaForwardFab(freshRoot)
                                if (fab != null) {
                                    Log.d(TAG, "Found Media Forward FAB, clicking (attempt $attempt)...")
                                    val rect = Rect()
                                    fab.getBoundsInScreen(rect)
                                    fabClicked = fab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    if (!fabClicked && rect.centerX() > 0) {
                                        clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                                        fabClicked = true
                                    }
                                    if (fabClicked) break
                                }
                            }
                            delay(300)
                        }

                        if (!fabClicked) {
                            val fallbackX = widthF - 110f
                            val fallbackY = heightF - 130f
                            Log.d(TAG, "Fallback clicking Forward FAB at ($fallbackX, $fallbackY)")
                            clickAt(fallbackX, fallbackY)
                        }
                        delay(1200) // Wait for Media Preview screen to open
                    }
                }

                // Step 2: Now we are on WhatsApp Media Preview Screen (or Chat)
                // If message caption was not set, try setting it in caption EditText if present
                if (task.message.isNotBlank()) {
                    val previewRoot = rootInActiveWindow
                    if (previewRoot != null) {
                        val captionEntry = findCaptionEntryNode(previewRoot)
                        if (captionEntry != null && captionEntry.text.isNullOrBlank()) {
                            Log.d(TAG, "Caption entry found empty, typing caption...")
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, task.message)
                            }
                            captionEntry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            delay(400)
                        }
                    }
                }

                // Step 3: Find and click green Send button on Media Preview screen
                var sendClicked = false
                for (attempt in 1..8) {
                    val freshRoot = rootInActiveWindow
                    if (freshRoot != null) {
                        val sendBtn = findWhatsAppSendButton(freshRoot)
                        if (sendBtn != null) {
                            Log.d(TAG, "Found WhatsApp Media Send button! Clicking (attempt $attempt)...")
                            val rect = Rect()
                            sendBtn.getBoundsInScreen(rect)
                            sendClicked = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (!sendClicked && rect.centerX() > 0) {
                                clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                                sendClicked = true
                            }
                            if (sendClicked) break
                        }
                    }
                    delay(300)
                }

                // Step 4: Fallback coordinate click for WhatsApp Send button on Media Preview screen
                if (!sendClicked) {
                    val fallbackX = widthF - 95f
                    val fallbackY = heightF - 120f
                    Log.d(TAG, "Fallback clicking Media Send button at ($fallbackX, $fallbackY)")
                    clickAt(fallbackX, fallbackY)
                    sendClicked = true
                }

                // Step 5: Mark task DONE in database and re-arm if recurring
                withContext(Dispatchers.IO) {
                    val db = (applicationContext as PostlyApplication).database
                    db.scheduleDao().markDone(task.id, System.currentTimeMillis())
                    AlarmScheduler.handleRecurringSchedule(applicationContext, task)
                }

                pendingTask = null
                Log.d(TAG, "WhatsApp media message successfully sent! Task marked DONE.")

                delay(1500)
                performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (e: Exception) {
                Log.e(TAG, "Error in WhatsApp media automation", e)
                RetryManager.handleFailure(applicationContext, task, "Media automation error: ${e.message ?: "Unknown"}")
                pendingTask = null
            } finally {
                isExecutingAutomation = false
            }
        }
    }

    private suspend fun searchAndSelectShareContact(targetName: String): Boolean {
        val cleanName = targetName.trim()
        Log.d(TAG, "Searching for contact in share screen: $cleanName")

        var searchClicked = false
        for (i in 1..6) {
            val root = rootInActiveWindow ?: continue
            val searchBtn = findSearchButton(root)
            if (searchBtn != null) {
                searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                searchClicked = true
                break
            }
            delay(300)
        }

        if (!searchClicked) {
            Log.w(TAG, "Could not find Search button in share screen")
            return false
        }

        delay(400)

        var searchInput: AccessibilityNodeInfo? = null
        for (i in 1..6) {
            val root = rootInActiveWindow ?: continue
            searchInput = findSearchInputNode(root)
            if (searchInput != null) break
            delay(300)
        }

        if (searchInput == null) {
            Log.w(TAG, "Could not find Search input field in share screen")
            return false
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanName)
        }
        searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(700)

        // Find matching contact node and click it
        for (attempt in 1..6) {
            val root = rootInActiveWindow ?: continue
            val contactNode = findFirstMatchingContactNode(root, cleanName)
            if (contactNode != null) {
                var clicked = contactNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) {
                    var parent = contactNode.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            break
                        }
                        parent = parent.parent
                    }
                }
                if (!clicked) {
                    val rect = Rect()
                    contactNode.getBoundsInScreen(rect)
                    if (rect.centerX() > 0) {
                        clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                        clicked = true
                    }
                }
                if (clicked) {
                    Log.d(TAG, "Selected contact '$cleanName' in share screen!")
                    return true
                }
            }
            delay(350)
        }

        Log.w(TAG, "Contact '$cleanName' not found in share screen results")
        return false
    }

    private fun findMediaForwardFab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/fab",
            "com.whatsapp.w4b:id/fab",
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/next_btn"
        )
        for (id in ids) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (!list.isNullOrEmpty() && list[0].isClickable) return list[0]
        }
        val nextWords = listOf("Next", "Send", "आगे", "भेजें")
        for (word in nextWords) {
            val list = root.findAccessibilityNodeInfosByText(word)
            for (node in list) {
                if (node.isClickable) return node
            }
        }
        return null
    }

    private fun findCaptionEntryNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/caption",
            "com.whatsapp.w4b:id/caption",
            "com.whatsapp:id/entry",
            "com.whatsapp.w4b:id/entry"
        )
        for (id in ids) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (!list.isNullOrEmpty()) return list[0]
        }
        return findFirstEditText(root)
    }

    private suspend fun searchAndOpenContact(targetName: String): Boolean {
        val cleanName = targetName.trim()
        Log.d(TAG, "Searching for contact: $cleanName")

        // Step 1: Click Search button
        var searchClicked = false
        for (i in 1..6) {
            val root = rootInActiveWindow ?: continue
            val searchBtn = findSearchButton(root)
            if (searchBtn != null) {
                searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                searchClicked = true
                break
            }
            delay(300)
        }

        if (!searchClicked) {
            Log.w(TAG, "Could not find Search button")
            return false
        }

        delay(400)

        // Step 2: Find search input and type recipientName
        var searchInput: AccessibilityNodeInfo? = null
        for (i in 1..6) {
            val root = rootInActiveWindow ?: continue
            searchInput = findSearchInputNode(root)
            if (searchInput != null) break
            delay(300)
        }

        if (searchInput == null) {
            Log.w(TAG, "Could not find Search input field")
            return false
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanName)
        }
        searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "Typed '$cleanName' into search bar. Waiting for results...")

        // Step 3: Wait for search results to load
        delay(1500)

        // Step 4: Find the exact contact in search results
        for (attempt in 1..6) {
            val root = rootInActiveWindow ?: continue
            val contactNode = findExactContactNode(root, cleanName)
            if (contactNode != null) {
                var clickable: AccessibilityNodeInfo? = contactNode
                while (clickable != null && !clickable.isClickable) {
                    clickable = clickable.parent
                }
                if (clickable != null) {
                    Log.d(TAG, "Found and clicking exact matched contact: $cleanName")
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(800)
                    return true
                }
            }
            delay(400)
        }

        Log.w(TAG, "Contact '$cleanName' not found in search results")
        return false
    }

    /**
     * Recursively searches for WhatsApp Send button across all versions (strictly excluding voice mic button)
     */
    private fun findWhatsAppSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidate = scanForSendButton(root)
        if (candidate != null) return candidate

        // Search by view IDs
        val ids = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/btn_send"
        )
        for (id in ids) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            for (node in list) {
                val desc = node.contentDescription?.toString() ?: ""
                if (!desc.contains("voice", ignoreCase = true)) {
                    return node
                }
            }
        }

        // Search by ContentDescription "Send" or "भेजें" (exact match)
        val sendWords = listOf("Send", "send", "भेजें")
        for (word in sendWords) {
            val textList = root.findAccessibilityNodeInfosByText(word)
            for (node in textList) {
                val desc = node.contentDescription?.toString() ?: ""
                val text = node.text?.toString() ?: ""
                if ((desc.equals(word, ignoreCase = true) || text.equals(word, ignoreCase = true)) && !desc.contains("voice", ignoreCase = true)) {
                    return node
                }
            }
        }

        return null
    }

    private fun scanForSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""

        // Strict: Must not be voice message!
        if (!desc.contains("voice", ignoreCase = true)) {
            if (desc.equals("Send", ignoreCase = true) || desc.equals("भेजें", ignoreCase = true) || id.endsWith(":id/send")) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = scanForSendButton(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Finds the message input box in a WhatsApp conversation.
     * Strictly verifies it is NOT a search bar.
     */
    private fun findChatMessageEntryNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp.w4b:id/entry",
            "com.whatsapp:id/conversation_text_entry"
        )
        for (id in ids) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            for (node in list) {
                val resId = node.viewIdResourceName ?: ""
                if (!resId.contains("search")) return node
            }
        }

        val messageNodes = root.findAccessibilityNodeInfosByText("Message")
        for (node in messageNodes) {
            val resId = node.viewIdResourceName ?: ""
            if (node.className?.contains("EditText") == true && !resId.contains("search")) {
                return node
            }
        }

        return null // Never fall back to search bar!
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

    private fun findExactContactNode(root: AccessibilityNodeInfo, targetName: String): AccessibilityNodeInfo? {
        val targetClean = targetName.trim()

        // 1. Strict match on chat row contact name view IDs
        val rowContactIds = listOf(
            "com.whatsapp:id/conversations_row_contact_name",
            "com.whatsapp.w4b:id/conversations_row_contact_name"
        )
        for (id in rowContactIds) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            for (node in list) {
                val nodeText = node.text?.toString()?.trim() ?: ""
                if (nodeText.equals(targetClean, ignoreCase = true) || nodeText.contains(targetClean, ignoreCase = true)) {
                    return node
                }
            }
        }

        // 2. Search by text, but EXCLUDE message snippets, subtitles, and edit texts!
        val byText = root.findAccessibilityNodeInfosByText(targetClean)
        for (node in byText) {
            val id = node.viewIdResourceName ?: ""
            if (id.contains("snippet") || id.contains("msg") || id.contains("search")) continue
            if (node.className?.contains("EditText") == true) continue

            val nodeText = node.text?.toString()?.trim() ?: ""
            if (nodeText.equals(targetClean, ignoreCase = true)) {
                return node
            }
        }
        return null
    }

    private fun findFirstMatchingContactNode(root: AccessibilityNodeInfo, targetName: String): AccessibilityNodeInfo? {
        return findExactContactNode(root, targetName)
    }

    private fun findPasswordField(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val list = rootNode.findAccessibilityNodeInfosByViewId("com.android.systemui:id/passwordEntry")
        if (!list.isNullOrEmpty() && list[0].className?.contains("EditText") == true) return list[0]
        return null
    }

    private fun findPinpadDigitNode(rootNode: AccessibilityNodeInfo, digit: String): AccessibilityNodeInfo? {
        val bounds = Rect()
        val minCenterY = (resources.displayMetrics.heightPixels * 0.45f).toInt()

        // Match strictly by Keyguard Keypad IDs across brands (Xiaomi, Samsung, OnePlus, Vivo, etc.)
        val idList = listOf(
            "com.android.systemui:id/key$digit",
            "com.android.keyguard:id/key$digit",
            "com.android.keyguard:id/digit_text",
            "com.miui.securitycenter:id/key_$digit",
            "com.miui.keyguard:id/key$digit",
            "com.android.systemui:id/button$digit",
            "com.android.systemui:id/digit_text",
            "com.android.systemui:id/digit_$digit",
            "com.android.systemui:id/key_$digit",
            "com.oplus.securitykeyboard:id/key_$digit",
            "com.sec.android.app.launcher:id/key$digit"
        )
        for (id in idList) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                node.getBoundsInScreen(bounds)
                if (bounds.centerY() > minCenterY && bounds.width() in 60..600 && bounds.height() in 60..600) {
                    if (node.text?.toString() == digit || node.contentDescription?.toString() == digit) {
                        return node
                    }
                }
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
            val stroke = GestureDescription.StrokeDescription(path, 0, 100)
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

            val intent = if (!task.attachmentUri.isNullOrBlank()) {
                val mediaUri = Uri.parse(task.attachmentUri)
                val mimeType = when (task.attachmentType) {
                    "IMAGE" -> "image/*"
                    "VIDEO" -> "video/*"
                    "AUDIO" -> "audio/*"
                    else -> "*/*"
                }

                try {
                    grantUriPermission(targetPkg, mediaUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {}

                val fullPhone = if (cleanPhone.length == 10 && (cleanPhone.startsWith("6") || cleanPhone.startsWith("7") || cleanPhone.startsWith("8") || cleanPhone.startsWith("9"))) "91$cleanPhone" else cleanPhone

                Intent(Intent.ACTION_SEND).apply {
                    setPackage(targetPkg)
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, mediaUri)
                    clipData = ClipData.newUri(contentResolver, "Attachment", mediaUri)
                    if (task.message.isNotBlank()) {
                        putExtra(Intent.EXTRA_TEXT, task.message)
                    }
                    if (fullPhone.isNotEmpty()) {
                        putExtra("jid", "$fullPhone@s.whatsapp.net")
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            } else if (cleanPhone.isNotEmpty()) {
                val fullPhone = if (cleanPhone.length == 10 && (cleanPhone.startsWith("6") || cleanPhone.startsWith("7") || cleanPhone.startsWith("8") || cleanPhone.startsWith("9"))) "91$cleanPhone" else cleanPhone
                val encodedMsg = URLEncoder.encode(task.message, "UTF-8")
                val uri = Uri.parse("whatsapp://send?phone=$fullPhone&text=$encodedMsg")
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
                Log.d(TAG, "Launched WhatsApp (attachment: ${!task.attachmentUri.isNullOrBlank()}). State initialized to START.")
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
