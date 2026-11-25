# Postly - Smart Message Scheduler & Automator

**Postly** is an Android message scheduling application inspired by SKEDit, built natively using **Kotlin**, **Jetpack Compose (Material 3)**, **Room Database**, and **Android AccessibilityService**.

---

## 🚀 Key Features

1. **WhatsApp Message Scheduling (Fully Functional):**
   - Direct contact and group scheduling.
   - Text messages with variable support (e.g. `{name}`).
   - Exact alarm scheduling using `AlarmManager.setExactAndAllowWhileIdle`.
   - Native UI interaction via `PostlyAccessibilityService`:
     - Launches WhatsApp to the target conversation.
     - Inputs message content automatically.
     - Simulates clicking the WhatsApp Send button.
     - Logs delivery status and updates Room DB to "Done".

2. **Full UI Platform Options (Matching SKEDit FAB & Drawer):**
   - WhatsApp (Working Engine)
   - WhatsApp Business
   - Telegram
   - Messenger
   - Instagram
   - Email
   - Call

3. **Scheduler Dashboard & Management:**
   - 5 Categorized Tabs: `Pending`, `Done`, `Deleted`, `Failed`, `Drafts`.
   - Empty state illustration with quick action.
   - Scheduled Cards with platform badge, date/time, and status checkmark.
   - Context Actions: `Send Now`, `Delete`, `Clear Pending`.

4. **Requirement Details Wizard:**
   - Accessibility Service setup.
   - Battery Optimization guide ("Don't optimize").
   - Display Over Other Apps overlay toggle.
   - Screen Lock bypass instructions.
   - Delivery Notifications permissions.

5. **Google Play Store Policy Compliant:**
   - **Prominent In-App Disclosure Dialog** displayed on first launch.
   - Official Accessibility Service description clearly stating purpose (no data harvesting, no keystroke tracking, local-only processing).
   - Safe branding and disclaimers.

---

## 📱 Pre-compiled APK Location

- **Ready-to-install APK:** `Postly.apk` (inside project root)
- **Direct Path:** `app/build/outputs/apk/debug/app-debug.apk`

### Quick Install via ADB:
```bash
adb install -r Postly.apk
```

---

## 🛠️ Tech Stack & Architecture

- **Language:** Kotlin 1.9.22
- **UI Framework:** Jetpack Compose + Material 3 (Material Icons Extended)
- **Architecture:** MVVM + Clean Service Architecture
- **Database:** AndroidX Room Database 2.6.1 (SQLite)
- **Background Tasks:** Android Exact AlarmManager + Foreground Service + WakeLock
- **Automation Core:** Android AccessibilityService
