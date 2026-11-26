# Postly - Project Overview & Developer Guide 📱🚀

> **Target Repository:** `git@github.com:Anil-0001/Postly---Whatsapp-messenger.git`  
> **Active Branch:** `main`  
> **Latest APK Deliverable:** `app/build/outputs/apk/debug/app-debug.apk` (or `Downloads/Postly.apk` on host PC)

---

## 📌 1. Yeh App Kis Liye Hai? (Purpose & Overview)

**Postly** ek native Android automation & scheduling application hai (jaisa **SKEDit** kaam karta hai).  
Iska mukhya uddeshya user ke dwara schedule kiye gaye WhatsApp messages (text, images, media) ko bina phone ko manually touch kiye, **automatic tareeke se** WhatsApp open karke samay par send karna hai.

### Key Highlights:
- **No Root Required:** 100% stock Android par kaam karta hai standard Android APIs ke zariye.
- **Play Store Compliant:** Android AccessibilityService API ke official guidelines ke tahat banaya gaya hai (In-App Disclosure dialog aur local data processing).
- **Offline & Battery Resilient:** Device ke sleep mode (Doze mode) se physical screen ko wake karke lockscreen PIN enter karta hai aur WhatsApp send karta hai.

---

## ⚙️ 2. Yeh App Kya-Kya Karta Hai? (Workflow & Execution Engine)

Jab koi message schedule hota hai, tab system is order me execute hota hai:

```
[User Schedule Create Karta Hai] 
               │
               ▼
   [Room Database me Save]
               │
               ▼
[Android AlarmManager: Exact Alarm set (30s mark par)]
               │
               ▼ (Schedule Time par AlarmReceiver trigger hota hai)
 [Step 1: Physical Screen WakeLock acquire & Screen ON]
               │
               ▼
 [Step 2: Network Connectivity Check]
               ├──► Offline? ──► Auto-retry schedule karega (agar user ne set kiya hai)
               └──► Online?  ──► Aage badhega
               │
               ▼
 [Step 3: Lockscreen Detection]
               ├──► Unlocked? ──► Direct WhatsApp launch
               └──► Locked?   ──► UnlockScreenActivity launch karega -> 
                                  PostlyAccessibilityService calibrated PIN tap karegi ->
                                  Keyguard unlock hote hi WhatsApp launch
               │
               ▼
 [Step 4: Targeted WhatsApp Launch]
               ├──► Text Only: whatsapp://send?phone=...&text=...
               └──► Image/Media: ACTION_SEND + FileProvider Stream + Caption + JID
               │
               ▼
 [Step 5: PostlyAccessibilityService Automation]
               ├──► Agar Contact Picker khula: Contact search -> select -> Green FAB Next click
               └──► Image Preview ya Chat khula: Caption verify -> Green Send Button click
               │
               ▼
 [Step 6: Status Update & Clean Exit]
               └──► Task marked "DONE" in Room DB -> Global BACK dabakar home screen par wapas
```

---

## ✅ 3. Abhi Isme Kya-Kya Ho Rakha Hai? (Completed & Working Features)

### A. WhatsApp Messaging & Media Automation:
1. **Direct Phone & Contact Selection:**
   - WhatsApp se live contact capture karne ke liye in-app contact picker.
   - Direct international format phone number (+91...) support.
2. **Gallery Media & Attachments:**
   - Gallery se Images, Documents, Videos, ya Audio files select karne ka option.
   - Large image thumbnail preview with file name and size pill.
   - Selected image ko **Edit (change)** aur **Delete (remove)** karne ke buttons.
   - Android `FileProvider` (`${applicationId}.fileprovider`) setup so WhatsApp has secure read access to internal attachment files without permission errors.
3. **Dedicated WhatsApp Media Automation (`startWhatsAppMediaAutomation`):**
   - WhatsApp image preview screen par caption auto-fill aur bottom-right green Send button click.
   - Contact forward/share screen par contact search and select + green FAB click.

### B. Screen Wakeup & Automated PIN Unlock:
1. **Physical Screen Wake:** `AlarmReceiver` screen ko sleep/doze mode se physically jagata hai (`SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`).
2. **Keyguard Dismissal:** `UnlockScreenActivity` lockscreen ko dismiss karne ki request karta hai.
3. **Calibrated OnePlus Nord CE 2 Lite (OxygenOS 14) Keypad:**
   - Physical resolution: `1080 x 2412` px.
   - Calibrated touch coordinates:
     - `Col 1:` 270px | `Col 2 (Center):` 540px | `Col 3:` 810px
     - `Row 1 (1, 2, 3):` 1380px
     - `Row 2 (4, 5, 6):` 1580px
     - `Row 3 (7, 8, 9):` 1780px
     - `Row 4 (0):` 1980px
     - `Row 4 Right (Enter / OK):` 810px, 1980px (auto-tap if device requires OK button).
4. **Universal Coordinate Scaling:** Any other phone resolution is dynamically scaled relative to the 1080x2412 baseline.

### C. Timing & Network Reliability:
1. **30-Second Alarm Alignment:** Message schedule hone par alarm `XX:XX:30` seconds par set hota hai taaki alarm se lekar WhatsApp send hone tak exact minute ke andar message send ho jaye.
2. **Auto-Retry on Network Failure:**
   - Agar internet/Wi-Fi band ho, toh user ke chune interval (1, 2, 3, 5, 10 min) ke baad 3 baar tak auto-retry karega.
   - **Default:** By default ye option **Off / Khali** rehta hai jab tak user khud select na kare.
   - Failed hone par card ke niche reason display hota hai (e.g., *"Network issue: Mobile data or Wi-Fi is off"*).

### D. UI & Dashboard:
- 5 Status Tabs: `Pending`, `Done`, `Deleted`, `Failed`, `Drafts`.
- Multi-platform selector: WhatsApp, WhatsApp Business, Telegram, Email, Call, etc.
- Requirements screen for Accessibility, Battery Optimization, Overlay permission, and PIN entry.

---

## 📱 4. Xiaomi / Redmi (MIUI 14 / HyperOS) Setup Guide

Redmi 11 Prime 5G ya kisi bhi Xiaomi device me background restrictions bahut strict hoti hain. Xiaomi phone me test karne ke liye ye steps zaroor karein:

1. **"Allow Restricted Settings" (Android 13 / MIUI 14 Mandatory):**
   - Jab aap settings me jakar Postly ki Accessibility ON karne lagte hain, toh MIUI kehta hai *"Restricted setting"*.
   - **Fix:** Phone Settings -> **Apps** -> **Manage apps** -> **Postly** open karein.
   - Upar right corner me **3 dots (⋮)** par click karein aur **"Allow restricted settings"** dabayein.
   - Ab Accessibility me jakar Postly ko **ON** karein.
2. **Autostart:**
   - Phone Settings -> Apps -> Manage apps -> Postly -> **Autostart: ON**.
3. **Other Permissions (Very Important):**
   - Phone Settings -> Apps -> Manage apps -> Postly -> **Other permissions**:
     - **"Show on Lock screen"** -> **Allow / Green check**.
     - **"Display pop-up windows while running in the background"** -> **Allow / Green check**.
4. **Battery Saver:**
   - Phone Settings -> Apps -> Manage apps -> Postly -> Battery Saver -> **No restrictions**.

---

## 🗂️ 5. Key Architecture & File Structure

```
app/src/main/
├── AndroidManifest.xml                        # Permissions, FileProvider, Services, Activities
├── res/xml/
│   ├── accessibility_service_config.xml       # Accessibility configuration
│   └── file_paths.xml                         # FileProvider storage mapping
└── java/com/postly/app/
    ├── MainActivity.kt                        # Entry activity, Navigation, In-app Disclosure
    ├── UnlockScreenActivity.kt                # Translucent activity to wake screen & dismiss lock
    ├── PostlyApplication.kt                   # Notification channels & DB singleton
    ├── data/
    │   ├── AppDatabase.kt                     # Room Database
    │   ├── dao/ScheduleDao.kt                 # CRUD queries, markDone, updateStatus
    │   └── model/ScheduleEntity.kt            # Schedule data model
    ├── service/
    │   ├── AlarmReceiver.kt                   # Wakes screen & triggers foreground service
    │   ├── DispatchForegroundService.kt       # Network check, lockscreen sync & WhatsApp launch
    │   ├── PostlyAccessibilityService.kt      # CORE AUTOMATION: PIN unlock, WhatsApp text & media send
    │   ├── BootReceiver.kt                    # Re-registers alarms on device reboot
    │   ├── AlarmScheduler.kt                  # Schedules exact alarms with AlarmManager
    │   └── RetryManager.kt                    # Handles retries & failure logging
    ├── ui/
    │   ├── screens/
    │   │   ├── MainSchedulerScreen.kt         # Dashboard with tabs (Pending, Done, Failed...)
    │   │   ├── CreateScheduleScreen.kt        # Schedule creator, media picker, retry dropdown
    │   │   └── RequirementsScreen.kt          # Permissions wizard & PIN setup
    │   └── theme/                             # Colors, Typography, Shapes
    └── util/
        └── NetworkUtils.kt                    # Real-time network availability checker
```

---

## 💻 6. Ghar Par Aage Ka Kaam Kaise Karein? (Home Work Setup)

### Step 1: Clone Repository
```bash
git clone git@github.com:Anil-0001/Postly---Whatsapp-messenger.git
cd Postly---Whatsapp-messenger
git checkout main
git pull origin main
```

### Step 2: System Requirements
- **JDK:** Java 17 (e.g. OpenJDK 17)
- **Android SDK:** API 34, Build Tools 34.0.0+
- **Android Studio:** Hedgehog / Iguana / Jellyfish (or Antigravity IDE)

### Step 3: Build APK
Terminal me ye command chalayein:
```bash
export JAVA_HOME=/path/to/your/jdk-17
./gradlew assembleDebug
```
Output APK file yahan banegi:
`app/build/outputs/apk/debug/app-debug.apk`

### Step 4: Install On Any Phone Via ADB
Apna phone USB debugging ON karke connect karein aur run karein:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 5: Live Debugging Logs
Live logs dekhne ke liye:
```bash
adb logcat -s PostlyAccessService:D DispatchFGService:D AlarmReceiver:D
```

---
*Created on 2026-11-26 for Postly WhatsApp Automation project.*
