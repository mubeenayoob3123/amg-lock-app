# AMG Lock — Customer Lock App (Phase 3)

**AMG platform ki Android lock app — Al Arab Electronic (pehli shop) ke liye.**

> **Sach baat pehle:** Ye **source code** hai, tayyar APK nahi.
> Is machine par Java / Android SDK nahi hai, is liye APK yahan **build
> ya test nahi hui**. APK Android Studio se banegi, aur asal phone par
> test Mubeen ke saath unke apne 1–2 phones par hogi (dummy users se).
> Neeche har cheez likhi hai: APK kaise banayein, phone par kaise lagayein,
> aur kya kya test karna hai.

---

# Roman Urdu — Seedha Samajh

## Ye app kya karti hai?

Customer jab phone qist par leta hai, to uske phone mein ye app hoti hai.

- **Qist waqt par de** → phone normal chalta hai, kuch nahi hota.
- **Qist late ho** → pehle **warning** aati hai, grace period (3 / 5 / 7 din) chalta hai.
- **Grace khatam ho jaye** → phone **lock** ho jata hai.
- **Qist ada kare** → phone **khud khul** jata hai.
- **Aakhri qist ada ho jaye** → phone **hamesha ke liye khul** jata hai, ye app khud uninstall ho jati hai, phone poora customer ka.

## Do hisse — dobara yaad rakhein

1. **Dashboard wala hissa** (pehle se bana hua hai) — shop ke computer par:
   lock / unlock ka faisla karta hai, pairing code deta hai, offline code deta hai.
2. **Phone wali app** (ye project) — customer ke phone mein:
   dashboard ka faisla sunti hai aur phone ko lock / unlock karti hai.

Dono ke beech mein baat is tarah hoti hai (har 15 minute, aur app kholne par):

```
Phone  →  "Meri qist ki halat kya hai?"  →  Dashboard Server
Phone  ←  stay_unlocked / warn / lock_now / permanent_unlock  ←  Dashboard Server
```

## Phone lock kaise hota hai? (Device Owner)

Android ka rule hai: koi bhi aam app phone lock nahi kar sakti.
Is liye phone **factory reset ke baad QR code se set** hota hai — is se
ye app **Device Owner** ban jati hai. Tab hi ye:

- Phone lock kar sakti hai, uninstall nahi ho sakti, Settings se factory reset nahi ho sakta.
- QR ka poora tareeqa: **`QR-PROVISIONING.md`** mein hai.

## Offline code kya hai?

Agar phone lock ho aur customer ke paas internet na ho, to dashboard se
ek code milta hai (jaise `YY28LV`). Shop wo code customer ko WhatsApp kar
de. Customer wo code lock screen par likhe → phone khul jaye ga.
**Ye code sirf ek dafa chalta hai** — istemal hote hi khatam.
Phone mein code ka **hash** save hota hai, asal code save nahi hota.

## Files — kya kahan hai?

```
lock-app/
├── README.md                  ← ye file
├── QR-PROVISIONING.md         ← QR se phone set karne ka poora tareeqa
├── TESTING.md                 ← test scenarios (dummy users se)
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/            ← gradle wrapper (Android Studio khud use kare ga)
└── app/
    ├── build.gradle.kts       ← app config (package pk.amgsystems.lock)
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/pk/amgsystems/lock/
        │   ├── LockApp.kt                ← app start, 15-min check-in schedule
        │   ├── MainActivity.kt           ← setup + status screen (register, check)
        │   ├── LockActivity.kt           ← Lock Screen 08 (offline code entry)
        │   ├── AmgDeviceAdminReceiver.kt ← Device Owner receiver
        │   ├── LockManager.kt            ← lock / unlock / permanent unlock, anti-tamper
        │   ├── PolicyEngine.kt           ← server ka jawab → lock action
        │   ├── ServerApi.kt              ← dashboard se baat (register / check-in)
        │   ├── CheckInWorker.kt          ← background check-in (WorkManager)
        │   ├── BootReceiver.kt           ← reboot ke baad lock wapas lagana
        │   ├── LockStateStore.kt         ← phone ki halat save karna
        │   ├── OfflineCodeStore.kt       ← offline code ka hash + ek-dafa istemal
        │   └── DeviceInfo.kt             ← phone ki pehchan (ANDROID_ID)
        └── res/                          ← layouts, colours (AMG navy/emerald), strings
```

---

# English — Build & Install

## Requirements

- **Android Studio** (Hedgehog / Iguana or newer) with **JDK 17**
- Android SDK **API 34**, Android device / emulator **Android 8.0+** (minSdk 26)
- Internet on first Gradle sync (dependencies download once)
- A **test phone** (1–2 of Mubeen's own phones) — factory reset for the real flow

## Build the APK

### Option A — Android Studio (recommended)

1. Android Studio → **Open** → select the `lock-app/` folder.
2. Let Gradle sync finish (first time 2–5 min).
3. **Debug APK** (testing): `Build → Build App Bundle(s) / APK(s) → Build APK(s)`
   → `app/build/outputs/apk/debug/app-debug.apk`
4. **Release APK** (real customer phones — QR provisioning needs this):
   `Build → Generate Signed Bundle / APK → APK →` create/use a keystore
   → `app/build/outputs/apk/release/app-release.apk`
   - **Keystore ko sambhal kar rakhein** — usi se har update sign hoga, aur QR ka checksum usi se banta hai (see `QR-PROVISIONING.md`).

### Option B — Command line

```bash
cd lock-app
./gradlew assembleDebug        # test APK
./gradlew assembleRelease      # release APK (needs signing config — see below)
```

Release signing (command line): `app/build.gradle.kts` mein signing config
add karein ya Android Studio se sign karein — keystore password kabhi
chat / git mein **na** likhein.

## Install on a test phone

**Real flow (customer jaisa):** factory reset → QR scan → register.
Poora tareeqa: **`QR-PROVISIONING.md`**.

**Shortcut (sirf test ke liye, bina reset ke):**

```bash
adb install app-debug.apk
adb shell dpm set-device-owner pk.amgsystems.lock/.AmgDeviceAdminReceiver
```

Ye sirf tab chalta hai jab phone par koi Google account na ho.
Phir app kholein → Server URL + pairing code (dashboard se) → **Register**.

## Server se connection

- Server URL app ki **Setup screen** mein likha jata hai (default:
  `https://alarab.amgsystems.pk` — paid domain, baad mein).
- **Free phase mein:** Hamraaz jo free hosting URL de, wahi likhein
  (jaise `https://amgsystems.web.app`).
- Endpoints (dashboard server flow se milte hain):
  - `POST /api/device/register` — `{ pairingCode, imei, model, androidVersion }`
  - `POST /api/device/checkin` — `{ deviceId, pairingCode }` →
    `stay_unlocked | warn | lock_now | permanent_unlock`
    (+ `dueDate`, `remainingAmount`, `graceDaysLeft`, `offlineUnlockCode` jab lock ho)
  - `POST /api/device/offline-code-used` — code istemal hone ki khabar
- Internet na ho to phone **purani halat** par rehta hai — internet band
  karke unlock nahi ho sakta, aur payment ke baad galat lock bhi nahi lagta.

## Test scenarios

Poori list **`TESTING.md`** mein hai. Mukhtasar:

| # | Test | Umeed (Expected) |
|---|---|---|
| 1 | Qist waqt par | Koi lock nahi, status ACTIVE |
| 2 | Qist miss → grace mein | Warning dikhe, phone chale |
| 3 | Grace khatam | Phone LOCK |
| 4 | Payment ada karein | Phone khud UNLOCK (15 min mein, ya "Abhi Check Karein" se foran) |
| 5 | Lock + internet band + offline code | Code se phone khule, code dobara na chale |
| 6 | Reboot (lock halat mein) | Lock wapas aaye |
| 7 | Reset / uninstall ki koshish | Block ho (Device Owner) |
| 8 | Aakhri qist | Permanent unlock, app khud uninstall |

---

# Kya tayyar hai, kya baqi hai

**Tayyar (is folder mein):**
- Poora Android source project (Kotlin, Device Owner, WorkManager check-in)
- Lock screen (Screen 08 design), warning flow, offline one-time code
- Auto lock / auto unlock / permanent unlock + self-uninstall
- Anti-tamper rules (uninstall block, factory-reset block, lock-task pinning)
- QR provisioning guide, build guide, test scenarios

**Baqi hai (asal phone par):**
- APK build karna (Android Studio — is machine par SDK nahi hai)
- QR se test phone provision karna + dummy user se 8 test scenarios
- Free hosting URL final karke Setup screen mein dalna
- Real-customer se pehle: `LockStateStore` ko EncryptedSharedPreferences
  par badalna, aur FCM push add karna taake payment par phone foran khule
  (abhi 15 minute tak lag sakte hain)

**Hadd (honest limits):** Safe Mode boot Android block nahi karne deta
(lock normal boot par wapas aata hai); recovery se forced reset + FRP hi
bachat hai; firmware flash karne wala expert kisi bhi software lock ko
tod sakta hai — ye aam customer ke liye hai, technician ke liye nahi.
