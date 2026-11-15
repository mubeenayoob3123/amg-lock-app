package pk.amgsystems.lock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import pk.amgsystems.lock.databinding.ActivityMainBinding

/**
 * Setup + status screen (the only screen the shop / customer sees
 * when the phone is NOT locked).
 *
 * Shop flow (in the shop, before handing the phone over):
 *  1. Phone is factory-reset and QR-provisioned (Device Owner).
 *  2. Is screen par PEHLE pairing code likhein (dashboard se mila
 *     8-harf wala code), server URL pehle se bhara hota hai — aam
 *     tor par usay chherne ki zaroorat nahi (3.10).
 *  3. Register dabayein. Kamyabi par sabz "Register ho gaya" card
 *     dikhta hai (account #, agli qist, aakhri check).
 *  4. Status ACTIVE. Hand the phone to the customer.
 *
 * Customer flow: this screen shows the current state in plain words —
 * ACTIVE / WARNING (grace chal rahi hai) / LOCKED / COMPLETED — plus
 * next due date, remaining amount and grace days left. "Abhi Check
 * Karein" forces an immediate server check-in (useful right after
 * a payment, instead of waiting up to 15 minutes for WorkManager).
 *
 * Device Owner gate (F-8/F-9, Wave 0.5): a phone that is not Device
 * Owner can never lock (the app is freely uninstallable and lock
 * task cannot hold), so registration is refused until Device Owner
 * is set, and a persistent red "not protected" banner stays on
 * screen — with registration blocked — until it is restored.
 *
 * Setup lock (F-12 / A-M8, 3.10): registration ke baad Setup fields
 * band ho jate hain — customer server URL ya code badal nahi sakta.
 * Dobara register sirf "Dobara Register Karein (Reset)" se hota hai,
 * jo shop ka Reset PIN maangta hai (PU-M8 / R-S13).
 *
 * v1.4.0 (audit round 2):
 *  - Reset PIN lazmi hai jab jari ho chuka ho, 5 ghalat koshishon par
 *    15 minute ki rok (J2-M15); server URL register ke baad lock hai
 *    (reset ke baad bhi wahi — sirf dobara QR provisioning se badalta hai).
 *  - Notification band ho to card dikhta hai + dobara maanga ja sakta
 *    hai (J1-m59); pairing code badalne ya server par register na
 *    rehne par dobara register ka banner dikhta hai (J1-m54 / J2-m34).
 *  - Setup mein Device ID (ANDROID_ID) dikhta hai (J1-m62) aur
 *    "Connection Test Karein" button hai (J1-p13).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: LockStateStore
    private lateinit var lockManager: LockManager
    private var registering = false
    private var checkingNow = false
    private var lastAutoCheckAttempt = 0L

    /** Android 13+ par notification permission (warning ke liye zaroori). */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Jawab aate hi card taaza karein (mana ho to card dikhe — J1-m59).
            refreshUi()
        }

    /**
     * QR pairing scan (A-M13 / Wave 5.6) — dashboard ka pairing QR
     * camera se parhein. Kamyab scan Setup pre-fill karta hai (server
     * URL + pairing code); Register phir bhi haath se dabana parta hai
     * taake shop code dekh kar tasdeeq kar sake. Cancel par kuch nahi.
     */
    private val qrScanLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val text = result.contents
            if (text.isNullOrBlank()) {
                // Scan cancel hua — koi ghalti nahi, khamoshi se wapas.
                return@registerForActivityResult
            }
            val payload = QrProvisioning.parsePairingQr(text)
            if (payload == null) {
                showSetupError(getString(R.string.qr_invalid))
                return@registerForActivityResult
            }
            // Server URL: lock ho to wahi rehta hai (J2-M15c); na ho to
            // allowlist se guzarna lazmi hai (J2-m30 — nakli server nahi).
            // URL na mane to code phir bhi bhar dete hain (code alag cheez hai).
            var urlProblem: String? = null
            if (payload.serverUrl.isNotBlank()) {
                when {
                    store.serverUrlLocked && !sameUrl(store.serverUrl, payload.serverUrl) ->
                        urlProblem = getString(R.string.qr_url_locked)
                    !QrProvisioning.isAllowedServerUrl(this, payload.serverUrl) ->
                        urlProblem = getString(R.string.qr_url_not_allowed)
                    else -> binding.etServerUrl.setText(payload.serverUrl)
                }
            }
            binding.etPairingCode.setText(payload.pairingCode)
            if (urlProblem != null) {
                showSetupError(urlProblem)
            } else {
                Toast.makeText(this, getString(R.string.qr_scanned_ok), Toast.LENGTH_LONG).show()
            }
        }

    /** Do URLs aik hi hain? (aakhri / aur capital ka farq nahi). */
    private fun sameUrl(a: String, b: String): Boolean =
        a.trim().trimEnd('/').lowercase() == b.trim().trimEnd('/').lowercase()

    /** Camera permission — QR scan ke liye zaroori (Android 6+). */
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchQrScan()
            } else {
                Toast.makeText(this, getString(R.string.qr_camera_denied), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = LockStateStore(this)
        lockManager = LockManager(this)

        // A-C1: Device Owner policies must be applied here too, not only
        // after QR provisioning. `adb shell dpm set-device-owner` (the
        // test route for the debug APK) never fires
        // onProfileProvisioningComplete, so without this
        // setLockTaskPackages() never runs and the lock cannot hold.
        // Safe to call on every launch — it no-ops when this app is not
        // Device Owner, and every policy call inside is idempotent.
        if (lockManager.isDeviceOwner()) {
            lockManager.applyDeviceOwnerPolicies()
        }

        // F-9: purani install mein dead default (alarab.amgsystems.pk)
        // stored ho to live server URL par le aayein (sirf jab abhi
        // register na hua ho — registered phone ka URL nahi chherte).
        if (!store.isRegistered && store.serverUrl == LockStateStore.LEGACY_DEAD_SERVER_URL) {
            store.serverUrl = LockStateStore.DEFAULT_SERVER_URL
        }

        // J2-M15(c) migration: pehle se registered phone (v1.3.0 se update)
        // ka URL bhi lock ho jaye — register ke baad URL badalna band hai.
        if (store.isRegistered && !store.serverUrlLocked) {
            store.serverUrlLocked = true
        }

        binding.etServerUrl.setText(store.serverUrl)
        binding.etPairingCode.setText(store.pairingCode)
        // J1-m62: Device ID (ANDROID_ID — IMEI nahi) enrollment par
        // dikhayein taake shop isay account se mila sake.
        binding.tvDeviceId.text = getString(R.string.device_id_label, DeviceInfo.androidId(this))

        binding.btnRegister.setOnClickListener { register() }
        binding.btnQrScan.setOnClickListener { startQrScan() }
        binding.btnTestConnection.setOnClickListener { testConnection() }
        binding.btnCheckNow.setOnClickListener { checkNow(silent = false) }
        binding.btnViewWarning.setOnClickListener {
            startActivity(Intent(this, WarningActivity::class.java))
        }
        binding.btnBatteryExempt.setOnClickListener { requestBatteryExemption() }
        binding.btnResetRegistration.setOnClickListener { confirmResetRegistration() }
        binding.btnNotificationsEnable.setOnClickListener { requestNotificationPermissionAgain() }
        binding.btnActionRegister.setOnClickListener { confirmResetRegistration() }
        binding.btnRetryCompletion.setOnClickListener { retryCompletion() }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // J2-M14: app kholne par bhi deadline check (offline lock).
        LocalDeadlineLock.evaluate(this)
        // J1-m58: FCM token retry (pehli koshish nakaam hui ho to).
        FcmManager.maybeRetryTokenFetch(this)
        refreshUi()
        when (store.lockState) {
            LockState.LOCKED -> {
                startActivity(
                    Intent(this, LockActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                )
            }
            LockState.COMPLETED -> {
                startActivity(Intent(this, CompletionActivity::class.java))
            }
            else -> {
                // Hifazati stopLockTask: agar kisi unlock path ke baad
                // task pinned reh gaya ho to yahan khul jaye (A-M4).
                runCatching { stopLockTask() }
                // F-14: app kholne par bhi check-in (agar aakhri kamyab
                // check-in ko 15 minute se zyada ho gaye hon) — taake
                // payment ke baad 15 minute ka intezar na ho.
                maybeAutoCheckIn()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Notification permission dobara maangein (J1-m59) — card ke button se. */
    private fun requestNotificationPermissionAgain() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            refreshUi()
            return
        }
        // "Dobara na poochein" lag chuka ho to system dialog nahi dikhaye
        // ga — tab seedha app ki Settings kholte hain.
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }.onFailure {
                Toast.makeText(this, getString(R.string.notifications_settings_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Kya notification ki ijazat mili hui hai (Android 13+ par check, us se pehle hamesha haan)? */
    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Completion dobara koshish (J2-m35) — Device Owner hatana baqi ho to. */
    private fun retryCompletion() {
        binding.btnRetryCompletion.isEnabled = false
        Thread {
            val cleared = lockManager.retryCompletion()
            runOnUiThread {
                binding.btnRetryCompletion.isEnabled = true
                if (cleared) {
                    startActivity(Intent(this, CompletionActivity::class.java))
                } else {
                    Toast.makeText(this, getString(R.string.completion_pending_toast), Toast.LENGTH_LONG).show()
                    refreshUi()
                }
            }
        }.start()
    }

    /**
     * Connection test (J1-p13) — server URL par server jawab de raha
     * hai ya nahi, register kiye baghair. Nateeja toast + setup error
     * line mein (kamyabi par error line saaf ho jati hai).
     */
    private var testingConnection = false

    private fun testConnection() {
        if (testingConnection) return
        var url = binding.etServerUrl.text.toString().trim()
        if (url.isBlank()) {
            url = LockStateStore.DEFAULT_SERVER_URL
            binding.etServerUrl.setText(url)
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
            binding.etServerUrl.setText(url)
        }
        if (!isValidUrl(url)) {
            showSetupError(getString(R.string.error_bad_url))
            return
        }
        testingConnection = true
        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = getString(R.string.testing_connection)
        Thread {
            val ok = ServerApi(this).testConnection(url)
            runOnUiThread {
                testingConnection = false
                binding.btnTestConnection.isEnabled = true
                binding.btnTestConnection.text = getString(R.string.test_connection_button)
                if (ok) {
                    binding.tvSetupError.text = ""
                    binding.tvSetupError.visibility = View.GONE
                    Toast.makeText(this, getString(R.string.test_connection_ok), Toast.LENGTH_LONG).show()
                } else {
                    showSetupError(getString(R.string.test_connection_failed))
                }
            }
        }.start()
    }

    /** F-33 / A-M10: battery optimisation se chhoot maangein (Doze defence). */
    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, getString(R.string.battery_open_failed), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * QR pairing scan shuru karein (A-M13): pehle camera permission,
     * phir ZXing scanner. Scan ka nateeja qrScanLauncher mein aata hai.
     */
    private fun startQrScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchQrScan()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchQrScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.qr_scan_prompt))
            setBeepEnabled(true)
            setOrientationLocked(true)
        }
        qrScanLauncher.launch(options)
    }

    private fun register() {
        // F-8 (Wave 0.5): refuse registration when the app is not Device
        // Owner. A phone that can never lock must never look registered —
        // the server would accept it and the shop would hand over an
        // unprotected phone believing it is protected.
        if (!lockManager.isDeviceOwner()) {
            showSetupError(getString(R.string.register_blocked_owner))
            refreshUi()
            return
        }
        val code = binding.etPairingCode.text.toString().trim().uppercase()
        var url = binding.etServerUrl.text.toString().trim()
        if (code.isBlank()) {
            showSetupError(getString(R.string.error_code_blank))
            return
        }
        // J2-M15(c): URL lock ho to field ki bajaye stored URL hi istemal
        // hota hai (field waise bhi band hoti hai — ye hifazati check hai).
        if (store.serverUrlLocked && store.serverUrl.isNotBlank()) {
            url = store.serverUrl
        }
        if (url.isBlank()) {
            url = LockStateStore.DEFAULT_SERVER_URL
            binding.etServerUrl.setText(url)
        }
        // Scheme theek karein: "amg-device-api.onrender.com" -> https://...
        // (http:// likha ho to isValidUrl use mana kar de ga — J1-m66.)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
            binding.etServerUrl.setText(url)
        }
        if (!isValidUrl(url)) {
            showSetupError(getString(R.string.error_bad_url))
            return
        }
        binding.tvSetupError.text = ""
        binding.tvSetupError.visibility = View.GONE
        registering = true
        binding.btnRegister.isEnabled = false
        binding.btnRegister.text = getString(R.string.registering)
        Thread {
            // URL sirf kamyabi par save hoti hai (ServerApi ke andar) —
            // ghalat URL purani registration kharab nahi karta (A-M8).
            val result = ServerApi(this).register(code, url)
            runOnUiThread {
                registering = false
                binding.btnRegister.isEnabled = true
                binding.btnRegister.text = getString(R.string.register_button)
                when (result) {
                    is ServerApi.RegisterResult.Success -> {
                        binding.etPairingCode.setText("")
                        Toast.makeText(this, getString(R.string.register_done), Toast.LENGTH_SHORT).show()
                        refreshUi()
                        // Pehli check-in foran — status taaza ho jaye.
                        checkNow(silent = true)
                    }
                    is ServerApi.RegisterResult.Failure -> {
                        // F-11 / A-M2: khaam server string nahi — saaf
                        // paigham + kya karna hai.
                        showSetupError(errorMessageFor(result.kind, result.error))
                    }
                }
                refreshUi()
            }
        }.start()
    }

    /**
     * Server URL ki jaanch (J1-m66 / J2-p16 / J1-p13):
     *  - SIRF https (http:// kabhi nahi — Render 301 par POST ko GET
     *    bana deta hai aur register 404 par nakaam hota tha).
     *  - Bare / adhoora URL nahi: host lazmi hai, aur domain hona
     *    chahiye (dot wala), IPv4, ya localhost (test ke liye).
     *    "https://", "https://a" waghera mana hain.
     */
    private fun isValidUrl(url: String): Boolean {
        return runCatching {
            val parsed = java.net.URL(url)
            if (parsed.protocol != "https") return false
            val host = parsed.host ?: ""
            if (host.isBlank() || host.length < 4) return false
            host.contains(".") ||
                host == "localhost" ||
                host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))
        }.getOrDefault(false)
    }

    /** Error kind se seedha sadha Roman Urdu paigham (F-11) — khaam server string kabhi nahi (J1-m71). */
    private fun errorMessageFor(kind: ServerApi.ErrorKind, rawError: String): String {
        // rawError sirf log ke liye hai — UI par kabhi nahi dikhana.
        if (rawError.isNotBlank()) {
            android.util.Log.w(TAG, "Register/check-in error kind=$kind raw=$rawError")
        }
        return when (kind) {
            ServerApi.ErrorKind.WRONG_CODE -> getString(R.string.error_wrong_code)
            ServerApi.ErrorKind.NO_INTERNET -> getString(R.string.error_no_internet)
            ServerApi.ErrorKind.SERVER_ASLEEP -> getString(R.string.error_server_asleep)
            ServerApi.ErrorKind.BAD_URL -> getString(R.string.error_bad_url)
            ServerApi.ErrorKind.REREGISTER_BLOCKED -> getString(R.string.error_reregister_blocked)
            ServerApi.ErrorKind.RATE_LIMITED -> getString(R.string.error_too_many_attempts)
            ServerApi.ErrorKind.SERVER_ERROR,
            ServerApi.ErrorKind.OTHER -> getString(R.string.error_server)
        }
    }

    private fun showSetupError(message: String) {
        binding.tvSetupError.text = message
        binding.tvSetupError.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun checkNow(silent: Boolean) {
        if (!store.isRegistered) {
            if (!silent) {
                Toast.makeText(this, getString(R.string.check_first_register), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (checkingNow) return
        checkingNow = true
        binding.btnCheckNow.isEnabled = false
        Thread {
            val outcome = ServerApi(this).checkIn()
            runOnUiThread {
                checkingNow = false
                binding.btnCheckNow.isEnabled = true
                when (outcome) {
                    is ServerApi.CheckInOutcome.Success -> {
                        PolicyEngine.apply(this, outcome.result)
                        if (outcome.result.instruction == "lock_now") {
                            startActivity(
                                Intent(this, LockActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                }
                            )
                        } else if (outcome.result.instruction == "permanent_unlock") {
                            startActivity(Intent(this, CompletionActivity::class.java))
                        } else if (!silent) {
                            Toast.makeText(this, getString(R.string.check_ok), Toast.LENGTH_SHORT).show()
                        }
                    }
                    is ServerApi.CheckInOutcome.ServerError -> {
                        PolicyEngine.recordCheckInServerError(this, outcome.error)
                        if (!silent) {
                            // J1-m54: pairing code badal gaya — naya code
                            // le kar dobara register karein (khaam error nahi).
                            val msg = when (outcome.error) {
                                "device_not_registered" -> getString(R.string.error_not_registered_server)
                                "pairing_code_changed", "invalid_pairing_code" ->
                                    getString(R.string.error_pairing_code_changed)
                                else -> getString(R.string.error_server)
                            }
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                    is ServerApi.CheckInOutcome.NetworkError -> {
                        PolicyEngine.recordFailure(this, PolicyEngine.statusFor(outcome.kind))
                        if (!silent) {
                            Toast.makeText(
                                this,
                                errorMessageFor(outcome.kind, ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                refreshUi()
            }
        }.start()
    }

    private fun maybeAutoCheckIn() {
        if (!store.isRegistered || checkingNow || registering) return
        val now = System.currentTimeMillis()
        if (now - lastAutoCheckAttempt < AUTO_CHECK_MIN_GAP_MS) return
        if (now - store.lastCheckInAt < CHECK_INTERVAL_MS) return
        lastAutoCheckAttempt = now
        checkNow(silent = true)
    }

    /**
     * Dobara register — shop ke Reset PIN ke baad (PU-M8 / R-S13, J2-M15).
     *  - PIN stored ho to PIN LAZMI hai — baghair PIN reset nahi hota.
     *  - PIN jari ho chuka ho lekin ab stored na ho (storage ka masla)
     *    to reset phone se NAHI ho sakta (fail open nahi) — dukaan se
     *    naya pairing code lena pare ga.
     *  - Sirf purani registration (PIN kabhi jari hi nahi hua) mein
     *    pehle jaisa confirm dialog hai — dobara register par server
     *    PIN de de ga, phir PIN laazim ho jaye ga.
     *  - 5 ghalat PIN koshishen → 15 minute ki rok (restart ke baad
     *    bhi barkarar rehti hai).
     */
    private fun confirmResetRegistration() {
        // Storage band ho to reset ka matlab hi nahi (dead store par
        // clearAll kuch mehfooz nahi mita ta) — pehle restart karein.
        if (SecurePrefs.degraded) {
            Toast.makeText(this, getString(R.string.reset_blocked_storage), Toast.LENGTH_LONG).show()
            return
        }
        val pin = store.resetPin
        if (pin.isBlank()) {
            if (store.resetPinIssued) {
                // PIN jari hua tha lekin ab stored nahi — fail open NAHI.
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.reset_title))
                    .setMessage(getString(R.string.reset_pin_lost))
                    .setPositiveButton(getString(R.string.reset_cancel), null)
                    .show()
                return
            }
            // Legacy: PIN kabhi jari nahi hua — purana confirm dialog
            // (dobara register par server PIN de de ga).
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.reset_title))
                .setMessage(getString(R.string.reset_message))
                .setPositiveButton(getString(R.string.reset_confirm)) { _, _ -> resetRegistration() }
                .setNegativeButton(getString(R.string.reset_cancel), null)
                .show()
            return
        }
        // Throttle: 5 ghalat koshishon ke baad 15 minute ki rok.
        val now = System.currentTimeMillis()
        if (now < store.resetPinLockedUntil) {
            val mins = (store.resetPinLockedUntil - now + 59_999L) / 60_000L
            Toast.makeText(
                this,
                getString(R.string.reset_pin_locked_out, mins),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.reset_pin_hint)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reset_pin_title))
            .setMessage(getString(R.string.reset_pin_message))
            .setView(input)
            .setPositiveButton(getString(R.string.reset_confirm)) { _, _ ->
                // Dialog khulne ke baad rok lag gayi ho to dobara check karein.
                if (System.currentTimeMillis() < store.resetPinLockedUntil) {
                    val mins = (store.resetPinLockedUntil - System.currentTimeMillis() + 59_999L) / 60_000L
                    Toast.makeText(this, getString(R.string.reset_pin_locked_out, mins), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val typed = input.text.toString().trim()
                when {
                    typed.isBlank() -> Toast.makeText(this, getString(R.string.reset_pin_blank), Toast.LENGTH_LONG).show()
                    typed == pin -> {
                        store.resetPinAttempts = 0
                        store.resetPinLockedUntil = 0L
                        resetRegistration()
                    }
                    else -> {
                        val attempts = store.resetPinAttempts + 1
                        if (attempts >= RESET_PIN_MAX_ATTEMPTS) {
                            store.resetPinAttempts = 0
                            store.resetPinLockedUntil =
                                System.currentTimeMillis() + RESET_PIN_LOCKOUT_MS
                            Toast.makeText(
                                this,
                                getString(R.string.reset_pin_locked_out, RESET_PIN_LOCKOUT_MINUTES),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            store.resetPinAttempts = attempts
                            Toast.makeText(this, getString(R.string.reset_pin_wrong), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.reset_cancel), null)
            .show()
    }

    private fun resetRegistration() {
        NotificationHelper.cancelWarning(this)
        OfflineCodeStore(this).clearAll()
        // J2-M15(c): reset ke baad bhi server URL wahi rehta hai (lock)
        // — customer phone ko kisi nakli server ki taraf nahi mod sakta.
        // URL sirf dobara QR provisioning se badalta hai.
        val lastUrl = store.serverUrl
        store.clearAll()
        store.serverUrl = lastUrl
        store.serverUrlLocked = true
        BootState.sync(this)
        // J1-m58: clearAll ne FCM token mita diya — foran dobara lein
        // (pehle app restart tak token nahi milta tha, push nahi aati thi).
        FcmManager.refreshTokenIfAvailable(this)
        binding.etPairingCode.setText("")
        binding.etServerUrl.setText(lastUrl)
        binding.tvSetupError.text = ""
        binding.tvSetupError.visibility = View.GONE
        Toast.makeText(this, getString(R.string.reset_done), Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun refreshUi() {
        // J2-M17: encrypted storage band ho to saaf banner — koi data
        // delete nahi hua, sirf is launch mein nazar nahi aa raha.
        binding.tvStorageError.visibility =
            if (SecurePrefs.degraded) View.VISIBLE else View.GONE

        // F-9 (Wave 0.5): Device Owner is re-checked on every launch
        // (onResume -> refreshUi). If it was lost, the phone is not
        // protected: show a persistent banner and keep registration
        // blocked until Device Owner is restored.
        val isOwner = lockManager.isDeviceOwner()
        binding.tvNotProtected.visibility = if (isOwner) View.GONE else View.VISIBLE
        binding.btnRegister.isEnabled = isOwner && !registering
        // J1-p14: QR-scan button bhi Device Owner ke baghair band —
        // pehle scan ho jata tha, phir Register mana kar deta tha.
        binding.btnQrScan.isEnabled = isOwner

        binding.tvDeviceOwner.text = if (isOwner) {
            getString(R.string.device_owner_yes)
        } else {
            getString(R.string.device_owner_no)
        }

        // J2-M15(c): server URL lock ho to field band (reset ke baad bhi).
        binding.etServerUrl.isEnabled = !store.serverUrlLocked
        binding.tvServerUrlLocked.visibility =
            if (!store.isRegistered && store.serverUrlLocked) View.VISIBLE else View.GONE

        // J1-m59: notification band ho to card dikhayein (warning ki
        // khabar isi se aati hai) + dobara maangne ka button.
        binding.llNotifications.visibility =
            if (store.isRegistered && !hasNotificationPermission()) View.VISIBLE else View.GONE

        // J1-m54 / J2-m34: dobara register ka banner — dukaan ne pairing
        // code badal diya ho, ya phone server par registered na raha ho.
        val lastStatus = store.lastCheckInStatus
        val codeChanged = lastStatus == PolicyEngine.CheckInStatus.serverError("pairing_code_changed") ||
            lastStatus == PolicyEngine.CheckInStatus.serverError("invalid_pairing_code")
        val notRegistered = store.consecutiveNotRegistered >= NOT_REGISTERED_PROMPT_AFTER
        binding.llActionBanner.visibility =
            if (store.isRegistered && (codeChanged || notRegistered)) View.VISIBLE else View.GONE
        if (codeChanged) {
            binding.tvActionText.text = getString(R.string.action_code_changed)
        } else if (notRegistered) {
            binding.tvActionText.text = getString(R.string.action_not_registered)
        }

        // J2-m35: completion adhoori ho (Device Owner hatna baqi) to
        // retry card dikhayein — phone khula hua hai, koi lock nahi.
        binding.llCompletionPending.visibility =
            if (store.completionPending) View.VISIBLE else View.GONE

        val statusText = when (store.lockState) {
            LockState.ACTIVE -> getString(R.string.status_active)
            LockState.WARNING -> getString(R.string.status_warning)
            LockState.LOCKED -> getString(R.string.status_locked)
            LockState.COMPLETED -> getString(R.string.status_completed)
        }
        binding.tvStatus.text = if (store.isRegistered) statusText
        else getString(R.string.status_not_registered)

        // 3.10: registered hone par Setup band, registered card khula.
        binding.llSetup.visibility = if (store.isRegistered) View.GONE else View.VISIBLE
        binding.llRegistered.visibility = if (store.isRegistered) View.VISIBLE else View.GONE
        if (store.isRegistered) {
            // J2-p17: pairing code poora chhupa hua (pehle aakhri 4 harf
            // dikhte thay — kandhe par se parhne wale ke liye kaafi thay).
            val maskedCode = "********"
            val info = StringBuilder()
            // J1-m55: dashboard ka account id (na ho to server wala).
            if (store.displayAccountId.isNotBlank()) {
                info.append("${getString(R.string.registered_account)} #${store.displayAccountId}\n")
            }
            info.append("${getString(R.string.pairing_code_label)}: $maskedCode")
            binding.tvRegisteredInfo.text = info.toString()
            // Success state (F-37): sabz check + agli qist + aakhri check.
            val success = StringBuilder(getString(R.string.setup_success_title))
            val nextDue = store.nextDueDate.ifBlank { store.dueDate }
            if (nextDue.isNotBlank()) {
                success.append("\n${getString(R.string.due_label)}: ${DateHelper.formatIsoDate(nextDue)}")
            }
            if (store.lastCheckInAt > 0) {
                success.append("\n${getString(R.string.last_check_label)}: ${DateHelper.formatDateTime(store.lastCheckInAt)}")
            }
            binding.tvRegisteredSuccess.text = success.toString()
            binding.tvRegisteredSuccess.visibility = View.VISIBLE
        } else {
            binding.tvRegisteredSuccess.visibility = View.GONE
        }

        // Warning banner — dabane par WarningActivity (F-19).
        binding.llWarningBanner.visibility =
            if (store.isRegistered && store.lockState == LockState.WARNING) View.VISIBLE else View.GONE

        // Battery exemption card (F-33 / A-M10) — sirf jab chhoot na ho.
        binding.llBattery.visibility =
            if (store.isRegistered && !isBatteryExempt()) View.VISIBLE else View.GONE

        val details = StringBuilder()
        if (store.isRegistered) {
            val nextDue = store.nextDueDate.ifBlank { store.dueDate }
            if (nextDue.isNotBlank()) {
                details.append("${getString(R.string.due_label)}: ${DateHelper.formatIsoDate(nextDue)}\n")
            }
            if (store.monthlyAmount > 0) {
                details.append("${getString(R.string.monthly_label)}: Rs ${store.monthlyAmount}\n")
            }
            details.append("${getString(R.string.remaining_label)}: Rs ${store.remainingAmount}\n")
            if (store.lockState == LockState.WARNING) {
                details.append("${getString(R.string.grace_label)}: ${store.graceDaysLeft} din\n")
            }
            details.append("${getString(R.string.shop_label)}: ${store.shopName}")
            if (store.shopPhone.isNotBlank()) details.append(" (${store.shopPhone})")
            // Offline-code window (D-M6) — agar active ho to dikhayein.
            // PU-N2 / R-N8: raw ISO nahi, aadmi ke parhne jaisa waqt.
            if (store.offlineUnlockUntil.isNotBlank()) {
                details.append("\n${getString(R.string.offline_window_label)}: ${DateHelper.formatIsoDateTime(store.offlineUnlockUntil)}")
            }
            // F-31: aakhri check-in ki halat saaf lafzon mein.
            val statusLine = checkInStatusText()
            if (statusLine.isNotBlank()) {
                details.append("\n$statusLine")
            }
        }
        binding.tvDetails.text = details.toString()
        binding.tvDetails.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Aakhri check-in ki halat — saaf lafzon mein (F-31). */
    private fun checkInStatusText(): String {
        val status = store.lastCheckInStatus
        if (status.isBlank() && store.lastCheckInAt <= 0L) return ""
        val whenText = when {
            store.lastCheckInAt > 0 -> DateHelper.formatDateTime(store.lastCheckInAt)
            store.lastCheckInAttemptAt > 0 -> DateHelper.formatDateTime(store.lastCheckInAttemptAt)
            else -> ""
        }
        val what = when {
            status == PolicyEngine.CheckInStatus.OK -> getString(R.string.check_status_ok)
            status == PolicyEngine.CheckInStatus.NO_INTERNET -> getString(R.string.check_status_no_internet)
            status == PolicyEngine.CheckInStatus.SERVER_SLOW -> getString(R.string.check_status_server_slow)
            status == PolicyEngine.CheckInStatus.UNKNOWN_INSTRUCTION -> getString(R.string.check_status_unknown)
            status.startsWith(PolicyEngine.CheckInStatus.SERVER_ERROR) -> {
                val code = status.substringAfter(":", "")
                when (code) {
                    "device_not_registered" -> getString(R.string.check_status_not_registered)
                    "pairing_code_changed", "invalid_pairing_code" -> getString(R.string.check_status_code_changed)
                    else -> getString(R.string.check_status_server_error)
                }
            }
            else -> ""
        }
        if (what.isBlank()) return ""
        return if (whenText.isNotBlank()) {
            "${getString(R.string.last_check_label)}: $whenText — $what"
        } else {
            "${getString(R.string.last_check_label)}: $what"
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val CHECK_INTERVAL_MS = 15L * 60L * 1000L
        private const val AUTO_CHECK_MIN_GAP_MS = 5L * 60L * 1000L
        /** Reset PIN ki ghalat koshishen — is ke baad rok (J2-M15b). */
        private const val RESET_PIN_MAX_ATTEMPTS = 5
        /** Reset PIN rok ki muddat — 15 minute (J2-M15b). */
        private const val RESET_PIN_LOCKOUT_MS = 15L * 60L * 1000L
        private const val RESET_PIN_LOCKOUT_MINUTES = 15L
        /** Lagatar kitni `device_not_registered` nakaamiyon par banner dikhe (J2-m34). */
        private const val NOT_REGISTERED_PROMPT_AFTER = 3
    }
}
