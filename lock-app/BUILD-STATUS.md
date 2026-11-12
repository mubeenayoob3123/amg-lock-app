# APK build status — 2026-11-11 (23:xx PKT)

## Result: NO APK BUILT YET

The debug APK could **not** be built on this Linux VM. This is an
environment/sandbox blocker, not a bug in the app code — no compilation
was ever reached, so no source files were changed.

## What IS installed now (durable, under ~/workspace)

- JDK: `~/workspace/tools/jdk-17.0.21+9` (Temurin OpenJDK 17.0.21)
- Android SDK: `~/workspace/android-sdk/`
  - `cmdline-tools/latest` (11076708)
  - `platform-tools` (adb, fastboot — r37.0.3)
  - `platforms/android-34` (android.jar, API 34)
  - `build-tools/34.0.0` (aapt2, d8, apksigner, zipalign)
  - `licenses/android-sdk-license` + `android-sdk-preview-license` (written)
- Gradle 8.7 wrapper distribution downloaded to `~/.gradle/wrapper/dists/`
- Egress CA certificates imported into the JDK truststore
  (`$JAVA_HOME/lib/security/cacerts`, 121 certs)
- Proxy settings for Gradle: `~/.gradle/gradle.properties` and
  `/root/.gradle/gradle.properties` (proxy host `hatch-egress-proxy:3128`)

## The blocker (exact)

Gradle builds always run through a **Gradle daemon**, and the Gradle
client talks to that daemon over **TCP on loopback (127.0.0.1)**.
On this VM the sandbox blocks TCP connections made by JVM processes:

- Gradle daemon starts fine, listens on 127.0.0.1, client TCP-connects
  fine, but the first message (the Build request) never arrives.
  Client error, every attempt:
  `FAILURE: Build failed with an exception. * What went wrong: Could not receive a message from the daemon.`
- Proof it is the sandbox, not Gradle: a minimal Java server + Java
  client on 127.0.0.1 got this reply instead of the server's data:
  `muse: Other TCP connections is turned off for this assistant. To allow
  it, ask the user to open Muse settings -> Permissions -> Direct network
  protocols and switch other_tcp from Deny to Ask.`
  The same test in Python (Python server + Python client) works fine —
  only JVM processes are intercepted.
- Other things ruled out: memory (daemon tried with -Xmx1536m and
  -Xmx1024m; a plain JVM allocates 900 MB fine), loopback itself
  (IPv4 and IPv6 loopback both work for non-JVM processes), sdkmanager
  (it has the same proxy problem, so the SDK packages were downloaded
  directly with curl from dl.google.com and unzipped by hand), swap
  (`swapon` is not permitted in this container).

## How to build the APK instead

### Option A — on a laptop with Android Studio (recommended)
1. Copy this whole `lock-app/` folder to the laptop.
2. Open it in Android Studio (it will download Gradle 8.7, AGP 8.5.2,
   Kotlin 1.9.24 and the AndroidX dependencies automatically).
3. Build → Build App Bundle(s) / APK(s) → Build APK(s), or run
   `./gradlew assembleDebug`.
4. The debug APK appears at `app/build/outputs/apk/debug/app-debug.apk`.
5. Install on the test phone with the platform-tools from this SDK
   (or Android Studio): `adb install -r app-debug.apk`
   (or `adb install -r -d app-debug.apk` if a version is already there).

### Option B — retry on this VM after the permission is enabled
If the Muse setting above is changed (`other_tcp` Deny → Ask, and the
prompt is allowed), rerun from this folder:
```bash
export JAVA_HOME=$HOME/workspace/tools/jdk-17.0.21+9
export ANDROID_HOME=$HOME/workspace/android-sdk
export ANDROID_SDK_ROOT=$HOME/workspace/android-sdk
export GRADLE_USER_HOME=$HOME/.gradle
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug -Dorg.gradle.jvmargs="-Xmx1024m -Dfile.encoding=UTF-8" -Dorg.gradle.workers.max=1
```
Expected output on success: `BUILD SUCCESSFUL` and the APK at
`app/build/outputs/apk/debug/app-debug.apk`.

## Notes
- No project source was edited during this attempt.
- `gradle.properties` in this project still says `-Xmx2048m`; on a
  low-memory machine use `-Xmx1024m` as above.
- The SDK zips (`cmdtools.zip`, `bt.zip`, `p34.zip`, `repo2.xml`) are
  kept in `~/workspace/android-sdk/` in case the SDK needs reinstalling.
