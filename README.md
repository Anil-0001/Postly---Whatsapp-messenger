# Postly - Smart Message Scheduler & Automator 📱

Postly is an open-source native Android message scheduling and automation application inspired by SKEDit, built using **Kotlin**, **Jetpack Compose (Material 3)**, **Room Database**, and **Android AccessibilityService**.

> 📖 **Complete Documentation & Developer Guide:**  
> For full architecture, workflow, current implementation details, device setups (OnePlus, Xiaomi/Redmi), and home setup instructions, please read **[PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md)**.

---

## 🚀 Key Features

1. **WhatsApp & Media Automation:**
   - Text message scheduling with recipient phone or WhatsApp contact picker.
   - Image, Video, Audio, and Document attachments from Gallery.
   - Built-in image preview, edit (replace), and delete controls.
   - Android `FileProvider` integration for secure WhatsApp media dispatch.
   - Accessibility automation handles both direct Image Preview and Contact Picker forward screens.

2. **Automated Screen Wakeup & PIN Unlock:**
   - Physical screen wakeup from Doze/Sleep mode via WakeLock.
   - Automated lockscreen swipe up and PIN entry.
   - Calibrated for OnePlus Nord CE 2 Lite (OxygenOS 14, 1080x2412) with universal scaling for all Android devices.

3. **Smart Timing & Network Auto-Retry:**
   - Precise 30-second scheduling interval (`XX:XX:30`).
   - Network availability check: optional auto-retry (1, 2, 3, 5, 10 mins with max 3 attempts) if offline.
   - Retry is off by default unless configured.

4. **Play Store Compliant:**
   - Prominent in-app disclosure dialog for Accessibility Service.
   - Local-only data processing with zero telemetry or remote credential storage.

---

## 🛠️ Quick Commands

### Build Debug APK:
```bash
./gradlew assembleDebug
```

### Install via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Live Logcat Stream:
```bash
adb logcat -s PostlyAccessService:D DispatchFGService:D AlarmReceiver:D
```
