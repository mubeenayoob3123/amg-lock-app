# QR Provisioning — Phone ko Device Owner banana

> Roman Urdu mein seedha: **Ye app tabhi phone lock kar sakti hai jab phone
> factory reset ke baad QR code se set ho.** Normal install se ye sirf
> ek aam app hai — lock nahi kare gi, uninstall rok nahi sakti.

## 1. Pehle se tayyari (shop par, phone dene se PEHLE)

1. Phone **factory reset** karein (Settings → System → Reset → Erase all data).
   - Phone par pehle se koi Google account / SIM lock nahi hona chahiye.
   - Reset ke baad phone Welcome screen par aaye ga — **"Hi there" / language wali screen**.
2. Phone ko shop ke **Wi-Fi** se connect karne ka intezam rakhein
   (QR mein Wi-Fi bhi likha hota hai, neeche dekhein).
3. APK **release signed** honi chahiye (debug APK se QR provisioning
   nahi hoti — debug signature har machine par alag hota hai).
   Release APK banane ka tareeqa README mein hai.

## 2. QR code kaise banayein

QR code mein ye JSON hota hai (ye **managed provisioning** ka standard format hai):

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
    "pk.amgsystems.lock/pk.amgsystems.lock.AmgDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM":
    "PASTE_YOUR_BASE64_SHA256_CHECKSUM_HERE",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":
    "https://YOUR-SERVER/amg-lock.apk",
  "android.app.extra.PROVISIONING_WIFI_SSID": "SHOP_WIFI_NAME",
  "android.app.extra.PROVISIONING_WIFI_PASSWORD": "SHOP_WIFI_PASSWORD",
  "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE": "WPA",
  "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
    "server_url": "https://alarab.amgsystems.pk"
  }
}
```

### Signature checksum kaise nikalein

Release APK banane ke baad (apna keystore use karke):

```bash
# 1. APK sign karein (Android Studio se bhi ho jata hai: Build > Generate Signed Bundle/APK)
# 2. Checksum nikalein:
keytool -printcert -jarfile app-release.apk | grep SHA256
# SHA256 ko base64 (URL-safe, bina padding) mein badlein — Android Studio /
# online tool "SHA256 to base64" se, ya:
python3 -c "
import hashlib, base64
h = bytes.fromhex('PASTE_SHA256_HEX_WITHOUT_COLONS')
print(base64.urlsafe_b64encode(h).decode().rstrip('='))
"
```

> **Ahem:** QR mein checksum **us hi keystore** ka hona chahiye jis se APK
> sign ki gayi hai. Ghalat checksum = provisioning fail.

### QR image kaise banayein

- JSON ko kisi QR generator mein daalein (offline tool behtar hai — ye
  shop ka Wi-Fi password bhi hai). Example: `qrencode` (Linux):
  ```bash
  qrencode -o provisioning-qr.png -s 8 < provisioning.json
  ```
- QR ko print karke shop par rakhein. **Ek QR ek hi APK version ke liye**
  hota hai — APK dobara sign ho to naya QR banayein.

## 3. Phone par QR scan (exact steps)

1. Factory-reset phone on karein → language chunein → **Welcome screen par
   6 dafa screen par tap karein** (kuch phones par QR scanner kholne ka
   yehi tareeqa hai; Samsung par: Welcome screen par "QR code" ka option
   bhi ho sakta hai).
   - Agar 6-tap se scanner na khule: Wi-Fi setup screen par QR icon dhoondhein,
     ya phone ko `adb` se provision karein (neeche "Testing shortcut" dekhein).
2. QR scan karein → phone APK download kare ga → **AMG Lock Device Owner ban jaye ga**.
3. Provisioning mukammal hote hi MainActivity khul jaye gi.
4. Dashboard se account ka **pairing code** lein (jaise `YD5NG6RK`),
   Server URL check karein, **Register** dabayein.
5. Status **ACTIVE** dikhe → phone customer ko dein.

## 4. Testing shortcut — bina factory reset ke (SIRF TEST KE LIYE)

Agar test phone par koi Google account nahi hai aur phone abhi reset nahi
karna, to computer se:

```bash
adb install app-debug.apk
adb shell dpm set-device-owner pk.amgsystems.lock/.AmgDeviceAdminReceiver
```

- Ye sirf tab chalta hai jab phone par **koi account add na ho** aur
  phone pehle se managed na ho. Warna error aaye ga:
  `Not allowed to set the device owner because there are already some accounts on the device.`
- Is shortcut se Device Owner mil jata hai, lekin **asal customer flow
  QR wala hi hai** — final test QR se hi karein.

## 5. Kya kya block hota hai (Device Owner ke baad)

| Cheez | Halat |
|---|---|
| App uninstall | Blocked (Device Owner rehte hue) |
| Factory reset (Settings se) | Blocked |
| Safe Mode boot | Android block nahi karne deta — lekin Safe Mode mein lock screen nahi chalti, aur normal boot par lock wapas aa jata hai; Device Owner role Safe Mode mein bhi rehta hai |
| Recovery se forced reset | Phone wipe ho sakta hai — phir **FRP (Factory Reset Protection)** + likha hua agreement hi bachat hai; setup par phone dobara enroll karna pare ga |
| Firmware flash (expert) | Koi bhi software lock 100% nahi rok sakta — aam customer ke liye kaafi hai |
