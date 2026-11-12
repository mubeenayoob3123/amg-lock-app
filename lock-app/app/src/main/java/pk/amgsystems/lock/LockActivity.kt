package pk.amgsystems.lock

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import pk.amgsystems.lock.databinding.ActivityLockBinding

/**
 * Lock Screen 08 — the screen the customer sees when the phone is locked.
 * Same design as the approved Figma Screen 08 (navy, big lock, due /
 * remaining amount, shop contact, offline code entry).
 *
 * Behaviour:
 *  - Back button is disabled. Home / Recents cannot escape while lock
 *    task mode is active (Device Owner). The status bar is disabled.
 *  - Offline code: the customer types the one-time code the shop gave
 *    them (from the dashboard). It is checked against the locally
 *    stored hash — NO internet needed. On success the code is burned
 *    and the phone unlocks. On failure nothing changes.
 *  - If the phone IS online, a successful offline unlock is also
 *    reported to the server, and a fresh check-in runs: if the qist
 *    is still unpaid the server will lock the phone again at the next
 *    check-in. The offline code is an emergency key, not a free pass.
 *  - While this screen is open it re-checks the state in onResume:
 *    if a payment unlocked the phone (e.g. via WorkManager), this
 *    screen closes itself and the phone is usable again.
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private lateinit var store: LockStateStore
    private lateinit var lockManager: LockManager
    private lateinit var offlineStore: OfflineCodeStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = LockStateStore(this)
        lockManager = LockManager(this)
        offlineStore = OfflineCodeStore(this)

        // Show above the keyguard, keep the screen on.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Back button must not close the lock screen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Intentionally empty — locked means locked.
            }
        })

        binding.btnOfflineUnlock.setOnClickListener { tryOfflineUnlock() }
    }

    override fun onResume() {
        super.onResume()
        if (store.lockState != LockState.LOCKED) {
            // We were unlocked (payment / permanent unlock) — leave.
            finish()
            return
        }
        // Pin the phone to this screen while Device Owner is active.
        if (lockManager.isDeviceOwner()) {
            runCatching { startLockTask() }
        }
        refreshUi()
    }

    private fun refreshUi() {
        binding.tvLockTitle.text = getString(R.string.locked_title)
        binding.tvLockReason.text = getString(R.string.locked_reason)
        binding.tvLockDetails.text =
            "${getString(R.string.locked_amount)}: Rs ${store.remainingAmount}\n" +
                "${getString(R.string.locked_due)}: ${store.dueDate.ifBlank { "-" }}"
        binding.tvShopContact.text = if (store.shopPhone.isNotBlank()) {
            "${store.shopName} — ${store.shopPhone}"
        } else {
            store.shopName
        }
        binding.tvOfflineError.text = ""
    }

    private fun tryOfflineUnlock() {
        val code = binding.etOfflineCode.text.toString().trim().uppercase()
        if (code.isBlank()) {
            binding.tvOfflineError.text = getString(R.string.offline_code_wrong)
            return
        }
        if (!offlineStore.hasCode()) {
            binding.tvOfflineError.text = "Koi offline code jari nahi hua — shop se code lein"
            return
        }
        if (!offlineStore.verifyAndBurn(code)) {
            binding.tvOfflineError.text = getString(R.string.offline_code_wrong)
            return
        }
        // Code was correct AND is now burned. Unlock locally first
        // (works fully offline), then tell the server if we can.
        lockManager.applyUnlock()
        Thread {
            ServerApi(this).reportOfflineCodeUsed(code)
            // Fresh check-in: if the qist is still unpaid, the server
            // will lock again — the offline code is not a free pass.
            ServerApi(this).checkIn()?.let { PolicyEngine.apply(this, it) }
        }.start()
        Toast.makeText(this, "Phone khul gaya", Toast.LENGTH_SHORT).show()
        finish()
    }
}
