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
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: LockStateStore
    private lateinit var lockManager: LockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = LockStateStore(this)
        lockManager = LockManager(this)

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
        val url = binding.etServerUrl.text.toString().trim()
        val code = binding.etPairingCode.text.toString().trim().uppercase()
        if (url.isBlank() || code.isBlank()) {
            Toast.makeText(this, "Server URL aur pairing code dono likhein", Toast.LENGTH_LONG).show()
            return
        }
        store.serverUrl = url
        binding.btnRegister.isEnabled = false
        Thread {
            val result = ServerApi(this).register(code)
            runOnUiThread {
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
        binding.tvDeviceOwner.text = if (lockManager.isDeviceOwner()) {
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
