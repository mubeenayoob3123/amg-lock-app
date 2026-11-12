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
- **Umeed:** Boot hote hi **lock screen wapas** aaye.
- **Karein:** Safe Mode mein boot karein (agar phone support karta hai).
- **Umeed (honest):** Safe Mode mein lock screen **nahi** dikhe gi (Android ka rule),
  lekin normal boot par lock **wapas** aaye ga — Device Owner role rehta hai.

## Scenario 6 — Aakhri qist (permanent unlock)

- **Karein:** Dummy account ki **tamam** qistein ada karein (ya early payoff).
- **Umeed:** Phone **hamesha ke liye khul** jaye, "Mubarak! Phone Aap Ka Hai"
  dikhe, Device Owner role khatam ho, aur **app khud uninstall** ho jaye.
- **Check karein:** Phone par app ka icon **ghaib** ho; Settings → Apps mein
  AMG Lock **na** ho; phone ko reset karke check karein ke koi lock nahi.
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
