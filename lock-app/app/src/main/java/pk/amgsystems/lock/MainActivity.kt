package pk.amgsystems.lock

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import pk.amgsystems.lock.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Setup + status screen (the only screen the shop / customer sees
 * when the phone is NOT locked).
 *
 * Shop flow (in the shop, before handing the phone over):
 *  1. Phone is factory-reset and QR-provisioned (Device Owner).
 *  2. This screen opens. Server URL is already filled in
 *     (change it in the free phase to the free hosting URL).
 *  3. Shop enters the pairing code from the AMG dashboard
 *     (account -> pairing code, e.g. YD5NG6RK) and taps Register.
 *  4. Status becomes ACTIVE. Hand the phone to the customer.
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
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: LockStateStore
    private lateinit var lockManager: LockManager
    private var registering = false

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

        binding.etServerUrl.setText(store.serverUrl)
        binding.etPairingCode.setText(store.pairingCode)

        binding.btnRegister.setOnClickListener { register() }
        binding.btnCheckNow.setOnClickListener { checkNow() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        // If a payment unlocked the phone while we were away, make sure
        // we are not still showing a stale LOCKED state.
        if (store.lockState == LockState.LOCKED) {
            startActivity(
                android.content.Intent(this, LockActivity::class.java)
            )
        }
    }

    private fun register() {
        // F-8 (Wave 0.5): refuse registration when the app is not Device
        // Owner. A phone that can never lock must never look registered —
        // the server would accept it and the shop would hand over an
        // unprotected phone believing it is protected.
        if (!lockManager.isDeviceOwner()) {
            Toast.makeText(this, getString(R.string.register_blocked_owner), Toast.LENGTH_LONG).show()
            refreshUi()
            return
        }
        val url = binding.etServerUrl.text.toString().trim()
        val code = binding.etPairingCode.text.toString().trim().uppercase()
        if (url.isBlank() || code.isBlank()) {
            Toast.makeText(this, "Server URL aur pairing code dono likhein", Toast.LENGTH_LONG).show()
            return
        }
        store.serverUrl = url
        registering = true
        binding.btnRegister.isEnabled = false
        Thread {
            val result = ServerApi(this).register(code)
            runOnUiThread {
                registering = false
                binding.btnRegister.isEnabled = true
                when (result) {
                    is ServerApi.RegisterResult.Success -> {
                        Toast.makeText(this, "Register ho gaya", Toast.LENGTH_SHORT).show()
                        checkNow()
                    }
                    is ServerApi.RegisterResult.Failure -> {
                        Toast.makeText(
                            this,
                            "Register nahi hua: ${result.error} — code dobara check karein",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                refreshUi()
            }
        }.start()
    }

    private fun checkNow() {
        if (!store.isRegistered) {
            Toast.makeText(this, "Pehle register karein", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnCheckNow.isEnabled = false
        Thread {
            val result = ServerApi(this).checkIn()
            runOnUiThread {
                binding.btnCheckNow.isEnabled = true
                if (result == null) {
                    Toast.makeText(
                        this,
                        "Internet nahi hai — purani halat hi dikhayi ja rahi hai",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    PolicyEngine.apply(this, result)
                    if (result.instruction == "lock_now") {
                        startActivity(
                            android.content.Intent(this, LockActivity::class.java)
                        )
                    }
                }
                refreshUi()
            }
        }.start()
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

        val details = StringBuilder()
        if (store.isRegistered) {
            if (store.dueDate.isNotBlank()) {
                details.append("${getString(R.string.due_label)}: ${store.dueDate}\n")
            }
            details.append("${getString(R.string.remaining_label)}: Rs ${store.remainingAmount}\n")
            if (store.lockState == LockState.WARNING) {
                details.append("${getString(R.string.grace_label)}: ${store.graceDaysLeft} din\n")
            }
            details.append("${getString(R.string.shop_label)}: ${store.shopName}")
            if (store.shopPhone.isNotBlank()) details.append(" (${store.shopPhone})")
            if (store.lastCheckInAt > 0) {
                val fmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
                details.append("\nAakhri check: ${fmt.format(Date(store.lastCheckInAt))}")
            }
        }
        binding.tvDetails.text = details.toString()
        binding.tvDetails.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
    }
}
