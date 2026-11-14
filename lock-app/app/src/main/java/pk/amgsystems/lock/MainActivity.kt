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
import androidx.core.content.ContextCompat
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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* koi zor nahi — na mile to warning screen phir bhi app kholne par dikhti hai */ }

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

        binding.etServerUrl.setText(store.serverUrl)
        binding.etPairingCode.setText(store.pairingCode)

        binding.btnRegister.setOnClickListener { register() }
        binding.btnCheckNow.setOnClickListener { checkNow(silent = false) }
        binding.btnViewWarning.setOnClickListener {
            startActivity(Intent(this, WarningActivity::class.java))
        }
        binding.btnBatteryExempt.setOnClickListener { requestBatteryExemption() }
        binding.btnResetRegistration.setOnClickListener { confirmResetRegistration() }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
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
        if (url.isBlank()) {
            url = LockStateStore.DEFAULT_SERVER_URL
            binding.etServerUrl.setText(url)
        }
        // Scheme theek karein: "amg-device-api.onrender.com" -> https://...
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

    private fun isValidUrl(url: String): Boolean {
        return runCatching {
            val parsed = java.net.URL(url)
            (parsed.protocol == "http" || parsed.protocol == "https") &&
                parsed.host.isNotBlank()
        }.getOrDefault(false)
    }

    /** Error kind se seedha sadha Roman Urdu paigham (F-11). */
    private fun errorMessageFor(kind: ServerApi.ErrorKind, rawError: String): String {
        return when (kind) {
            ServerApi.ErrorKind.WRONG_CODE -> getString(R.string.error_wrong_code)
            ServerApi.ErrorKind.NO_INTERNET -> getString(R.string.error_no_internet)
            ServerApi.ErrorKind.SERVER_ASLEEP -> getString(R.string.error_server_asleep)
            ServerApi.ErrorKind.BAD_URL -> getString(R.string.error_bad_url)
            ServerApi.ErrorKind.REREGISTER_BLOCKED -> getString(R.string.error_reregister_blocked)
            ServerApi.ErrorKind.RATE_LIMITED -> getString(R.string.error_too_many_attempts)
            ServerApi.ErrorKind.SERVER_ERROR,
            ServerApi.ErrorKind.OTHER -> getString(R.string.error_server, rawError)
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
                        PolicyEngine.recordFailure(
                            this,
                            PolicyEngine.CheckInStatus.serverError(outcome.error)
                        )
                        if (!silent) {
                            val msg = if (outcome.error == "device_not_registered") {
                                getString(R.string.error_not_registered_server)
                            } else {
                                getString(R.string.error_server, outcome.error)
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
     * Dobara register — shop ke Reset PIN ke baad (PU-M8 / R-S13).
     * PIN server register jawab mein deta hai (v2.1) aur encrypted
     * store mein rehta hai. PIN ke baghair koi phone utha kar
     * registration wipe karke dobara register nahi kar sakta.
     * Purani registration jis mein PIN stored nahi (server ne abhi
     * PIN nahi diya tha) — wahan pehle jaisi tasdeeq hoti hai, aur
     * dobara register par naya server PIN de de ga.
     */
    private fun confirmResetRegistration() {
        val pin = store.resetPin
        if (pin.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.reset_title))
                .setMessage(getString(R.string.reset_message))
                .setPositiveButton(getString(R.string.reset_confirm)) { _, _ -> resetRegistration() }
                .setNegativeButton(getString(R.string.reset_cancel), null)
                .show()
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
                val typed = input.text.toString().trim()
                when {
                    typed.isBlank() -> Toast.makeText(this, getString(R.string.reset_pin_blank), Toast.LENGTH_LONG).show()
                    typed == pin -> resetRegistration()
                    else -> Toast.makeText(this, getString(R.string.reset_pin_wrong), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(getString(R.string.reset_cancel), null)
            .show()
    }

    private fun resetRegistration() {
        NotificationHelper.cancelWarning(this)
        OfflineCodeStore(this).clearAll()
        store.clearAll()
        binding.etPairingCode.setText("")
        binding.etServerUrl.setText(LockStateStore.DEFAULT_SERVER_URL)
        binding.tvSetupError.text = ""
        binding.tvSetupError.visibility = View.GONE
        Toast.makeText(this, getString(R.string.reset_done), Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun refreshUi() {
        // F-9 (Wave 0.5): Device Owner is re-checked on every launch
        // (onResume -> refreshUi). If it was lost, the phone is not
        // protected: show a persistent banner and keep registration
        // blocked until Device Owner is restored.
        val isOwner = lockManager.isDeviceOwner()
        binding.tvNotProtected.visibility = if (isOwner) View.GONE else View.VISIBLE
        binding.btnRegister.isEnabled = isOwner && !registering

        binding.tvDeviceOwner.text = if (isOwner) {
            getString(R.string.device_owner_yes)
        } else {
            getString(R.string.device_owner_no)
        }

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
            val maskedCode = store.pairingCode.let { code ->
                if (code.length > 4) "****" + code.takeLast(4) else "****"
            }
            val info = StringBuilder()
            if (store.accountId.isNotBlank()) {
                info.append("${getString(R.string.registered_account)} #${store.accountId}\n")
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
                if (code == "device_not_registered") getString(R.string.check_status_not_registered)
                else getString(R.string.check_status_server_error)
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
        private const val CHECK_INTERVAL_MS = 15L * 60L * 1000L
        private const val AUTO_CHECK_MIN_GAP_MS = 5L * 60L * 1000L
    }
}
