# Testing — AMG Lock App (Dummy Users, Mubeen ke apne phones par)

> **Rule:** Asal customer se pehle **sirf dummy / test users** se test karein,
> Mubeen ke apne 1–2 phones par. Har test ke baad dashboard mein check karein
> ke audit log mein sahi entry aayi ya nahi.

## Tayyari (har test se pehle)

1. Test phone **factory reset** karein → QR se provision karein
   (`QR-PROVISIONING.md`) → dashboard se **dummy customer** banayein
   (naam: `Test User 1`, CNIC: test ke liye, **asal CNIC na likhein**).
2. Dummy account banayein: advance + qistein + due date aaj ya kal ki.
3. Pairing code dashboard se lein → phone par Register karein → status ACTIVE.

---

## Scenario 1 — Qist waqt par (no lock)

- **Karein:** Due date se pehle payment dashboard mein add karein.
- **Umeed:** Phone normal chale, koi warning / lock nahi. Dashboard: ACTIVE.
- **Check karein:** Phone par "Abhi Check Karein" dabayein → status ACTIVE rahe.

## Scenario 2 — Qist miss → warning, phir lock

- **Karein:** Due date guzar jaye, payment **na** karein.
- **Umeed (pehle):** Phone par warning dikhe — "Qist Late Hai, grace period
  chal raha hai" — phone abhi bhi chale.
- **Umeed (grace khatam par):** Grace 3 / 5 / 7 din (jo account mein set hai)
  khatam hote hi phone **LOCK** ho jaye. Lock screen (Screen 08) dikhe:
  baqi raqam + due date + shop ka naam/number.
- **Check karein:** Dashboard Device Lock tab mein status LOCKED + audit entry.

## Scenario 3 — Payment = unlock

- **Karein:** Locked phone ki qist dashboard mein ada karein.
- **Umeed:** Phone **khud khul** jaye — zyada se zyada 15 minute mein
  (WorkManager), ya phone par "Abhi Check Karein" se foran.
- **Check karein:** Dashboard mein status ACTIVE, audit mein "unlocked (payment ke baad auto)".

## Scenario 4 — Offline unlock code

- **Karein:** Phone locked ho → phone ka **internet band** karein (Wi-Fi + data off)
  → dashboard se offline code lein → customer ko code dein.
- **Umeed:** Customer code lock screen par likhe → phone **khul** jaye,
  **bina internet**.
- **Phir dobara:** Wohi code dobara likhein → **na khule** (code ek dafa hi chalta hai).
- **Check karein:** Internet on karke check-in karein — agar qist abhi bhi
  baqi hai to phone **dobara lock** ho jaye ga (offline code free pass nahi hai).

## Scenario 5 — Reset / uninstall ki koshish (tamper test)

- **Karein:** Locked phone par Settings → Apps → AMG Lock → Uninstall dabayein.
- **Umeed:** Uninstall **block** ho ("Uninstall blocked by your IT admin" jaisa message).
- **Karein:** Settings → Reset → Factory reset ki koshish karein.
- **Umeed:** Reset option **block** ho ya Device Owner ki wajah se na ho.
- **Karein:** Phone restart karein (locked halat mein).
- **Umeed:** Boot hote hi **lock screen wapas** aaye (PIN se pehle bhi —
  locked boot par lock dobara lag jata hai, v1.4.0).
- **Karein:** Safe Mode mein boot karein (agar phone support karta hai).
- **Umeed (v1.4.0):** Safe Mode **block** hai (DISALLOW_SAFE_BOOT) — phone
  Safe Mode mein boot nahi hoga, normal boot par lock waisa hi rehta hai.
- **Note (v1.4.0):** Policies lagne ke baad **USB debugging / adb bhi band**
  ho jata hai (DISALLOW_DEBUGGING_FEATURES) — yehi hifazat hai. adb /
  logcat sirf **policies lagne se pehle** (setup ke waqt) istemal hota hai.

## Scenario 6 — Aakhri qist (permanent unlock)

- **Karein:** Dummy account ki **tamam** qistein ada karein (ya early payoff).
- **Umeed:** Phone **hamesha ke liye khul** jaye, "Mubarak! Phone Aap Ka Hai"
  dikhe, Device Owner role khatam ho. **App khud uninstall NAHI hoti** —
  customer ko "App Uninstall Karein" button dabana parta hai (phir Android
  ka uninstall prompt khulta hai).
- **Check karein:** Button dabane ke baad phone par app ka icon **ghaib** ho;
  Settings → Apps mein AMG Lock **na** ho; phone ko reset karke check karein
  ke koi lock nahi.
- **Check karein:** Dashboard mein account **Completed / Clear** + clearance receipt.

## Scenario 7 — Partial payment (aadhi qist)

- **Karein:** Overdue qist ka **aadha** paisa ada karein (poori qist nahi).
- **Umeed:** Phone **locked hi rahe** — jab tak poori overdue qist ada na ho,
  unlock nahi hota.
- **Karein:** Baqi aadha ada karein.
- **Umeed:** Phone khul jaye.

## Scenario 8 — Internet band karke unlock ki koshish (cheating test)

- **Karein:** Locked phone ka internet band karein, 15 minute wait karein.
- **Umeed:** Phone **locked hi rahe** — internet band karke unlock nahi hota,
  kyunki phone purani halat par rehta hai.
- **Lambi muddat (v1.4.0):** Internet band rakhna ab hamesha ke liye bachat
  nahi hai — dekhein Scenario 12 (local deadline lock).

## Scenario 9 — QR pairing scan (Wave 5.6 / A-M13)

- **Karein:** Dashboard se account ka **pairing QR** dikhayein → phone par
  Setup mein **"QR Scan Karein"** dabayein → QR scan karein.
- **Umeed:** Server URL + pairing code **khud bhar** jayein, aur
  "QR se code bhar diya gaya — ab Register dabayein" dikhe.
- **Karein:** Phir **Register** dabayein (scan khud register nahi karta).
- **Umeed:** Register ho jaye, status ACTIVE.
- **Ghalat QR:** Koi aur QR (masalan Wi-Fi wala) scan karein.
- **Umeed:** "Ye QR AMG ka pairing QR nahi lagta" wali ghalti dikhe,
  Setup kharab na ho.

## Scenario 10 — FCM push: payment ke baad foran unlock (Wave 5.4)

> Sirf tab jab Firebase setup ho chuka ho (google-services.json +
> FCM service account Render mein — `FCM_SERVICE_ACCOUNT_JSON` env var,
> dekhein `hidden_files/fcm-setup-guide.md`). Purana `FCM_SERVER_KEY`
> ab kaam nahi karta (Google ne legacy FCM API June 2024 mein band kar di).
> Setup ke baghair ye test NAHI chalega — tab Scenario 3 wala 15-minute
> unlock hi umeed hai.

- **Karein:** Phone locked ho → dashboard mein qist ada karein → phone ko
  **haath na lagayein** (na "Abhi Check Karein", na app kholein).
- **Umeed:** Phone **1 minute ke andar khud khul** jaye (push par foran
  check-in hota hai).
- **Check karein:** Dashboard Device Lock tab mein phone ka FCM token
  registered nazar aaye (`fcmRegistered`); server `/health` mein
  `fcmConfigured: true` ho.

## Scenario 11 — QR provisioning extras (Wave 5.6)

- **Karein:** Provisioning QR mein admin extras bundle rakhein
  (`server_url` + `pairing_code` — dekhein `QR-PROVISIONING.md`) →
  factory-reset phone provision karein.
- **Umeed:** Provisioning ke baad MainActivity khule to Setup **pehle se
  bhara** ho (server URL + pairing code) — sirf Register dabana pare.
- **Baghair extras:** Extras ke baghair provision karein.
- **Umeed:** Setup khaali khule (server URL default), haath se bharna pare.

## Scenario 12 — Local deadline lock: offline reh kar lock se bachna (v1.4.0)

> Naya rule (Mubeen ka faisla, V2 §1 mein tabdeeli): phone ke paas aakhri
> sync wala schedule hai — agar phone **due date + grace + 48 ghante**
> tak offline rahe to wo **khud lock** ho jata hai, bina server se pooche.
> 48 ghante ka buffer genuine outage ke liye hai (gaaon / recharge khatam).

- **Karein:** Dummy account ki qist due karwayein (due date guzar jaye,
  payment na karein) → phone ka internet band karein → 48 ghante + grace
  guzarne dein (test ke liye grace chhota rakhein, ya phone ki tareekh
  aage kar ke check karein — waqt badalna sirf test ke liye hai).
- **Umeed:** Phone **khud lock** ho jaye, lock screen par offline wala
  paigham dikhe: "Internet nahi hai aur qist ki tareekh guzar gayi".
- **Karein:** Internet on karein → dashboard mein qist ada karein →
  "Abhi Check Karein" dabayein.
- **Umeed:** Phone **foran khul** jaye (kamyab check-in local lock saaf
  kar deta hai).
- **Karein:** Aisi account jis ki koi qist overdue nahi, us ka phone
  internet band karke chhor dein.
- **Umeed:** Phone **kabhi lock na ho** — schedule mein kuch overdue nahi
  to local lock nahi lagta.

## Scenario 13 — Factory reset ke baad dobara enroll (J1-m52)

> Factory reset Device Owner mita deta hai. Phone dobara enroll karne ke
> liye **dashboard se pairing code dobara banana (regenerate)** zaroori hai
> — purana code kaam nahi kare ga (re-register guard).

- **Karein:** Phone factory reset karein → QR se dobara provision karein →
  **purana** pairing code likh kar Register karein.
- **Umeed:** Register **na ho** — saaf paigham dikhe: "dobara register
  karne ke liye dukaan se naya pairing code lein".
- **Karein:** Dashboard mein account ka pairing code **regenerate** karein
  → **naya** code phone par likh kar Register karein.
- **Umeed:** Register ho jaye, status ACTIVE. Purana phone ka record
  dashboard mein wahi account dikhaye (naya account nahi banta).

---

## Test result likhne ka tareeqa

Har scenario ke baad ye likhein (paper ya notes mein):

```
Test: Scenario 2 (warning → lock)
Phone: Samsung A54 (Test User 1)
Date/Time: ______
Result: PASS / FAIL
Kya hua: ______
Dashboard audit: ______
```

FAIL ho to screenshot + waqt likh kar Hamraaz ko bhejein — wahi fix hoga.
