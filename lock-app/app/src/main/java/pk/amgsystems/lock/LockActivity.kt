package pk.amgsystems.lock

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 *  - Amount dikhaya jata hai OVERDUE qist ka (F-21 / 3.5) — kul baqi
 *    raqam nahi. Server jab overdueAmount bhejta hai to wahi, warna
 *    purani kul raqam (fallback). Saath mein: kitni qistein late hain,
 *    kitne din late, phone kab se lock hai, agla check kab ho ga (F-24, F-29).
 *  - "Abhi Check Karein" (F-28): payment ke baad customer khud dobara
 *    check kar sakta hai — 15 minute ka intezar nahi.
 *  - Emergency call (1122) aur shop ko call (F-22): dialer lock-task
 *    allowlist mein hai (LockManager.refreshLockTaskPackages), is liye
 *    ye buttons lock screen se bhi chalte hain.
 *  - Offline code: the customer types the one-time code the shop gave
 *    them. It is checked against the locally stored hash — NO internet
 *    needed. On success the code is burned and the phone unlocks.
 *    Istemal shuda code dobara nahi chalta, chahe offline istemal hua
 *    ho (A-M3 — used-hash record + pending server report).
 *  - Lock screen background unlock par khud band hoti hai (F-26 / A-M5):
 *    lock-state ka listener state badalte hi stopLockTask() + finish()
 *    karta hai. Har unlock path par stopLockTask() chalta hai, warna
 *    customer Setup screen mein phans jata hai (pin trap, A-M4 / F-27).
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private lateinit var store: LockStateStore
    private lateinit var lockManager: LockManager
    private lateinit var offlineStore: OfflineCodeStore
    private var checking = false

    /** F-26 / A-M5: background unlock par lock screen khud band ho. */
    private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != LockStateStore.KEY_LOCK_STATE) return@OnSharedPreferenceChangeListener
        runOnUiThread { onLockStateChanged() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = LockStateStore(this)
        lockManager = LockManager(this)
        offlineStore = OfflineCodeStore(this)
        store.addListener(stateListener)

        // Show above the keyguard, wake the screen.
        // A-n9: FLAG_KEEP_SCREEN_ON hata diya — raat ko lock hone par
        // screen poori raat jalti rehti thi aur battery khatam hoti thi.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Back button must not close the lock screen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Intentionally empty — locked means locked.
            }
        })

        binding.btnOfflineUnlock.setOnClickListener { tryOfflineUnlock() }
        binding.btnCheckAgain.setOnClickListener { checkAgain() }
        binding.btnEmergency.setOnClickListener { dialNumber(EMERGENCY_NUMBER) }
        binding.btnCallShop.setOnClickListener { dialNumber(store.shopPhone) }
    }

    override fun onDestroy() {
        super.onDestroy()
        store.removeListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        if (store.lockState != LockState.LOCKED) {
            // We were unlocked (payment / permanent unlock) — leave.
            dismissLockScreen()
            return
        }
        // Pin the phone to this screen while Device Owner is active.
        // A-M12: a failed pin used to be silent (runCatching, no log),
        // so a broken lock looked identical to a working one. Now a
        // failure is logged loudly, the Device Owner policies are
        // re-applied (a missing setLockTaskPackages is the usual
        // cause), and we fall back to locking the screen at once.
        if (lockManager.isDeviceOwner()) {
            runCatching { startLockTask() }
                .onFailure { e ->
                    Log.e(TAG, "startLockTask() FAILED — phone is NOT pinned, Home can escape the lock", e)
                    lockManager.applyDeviceOwnerPolicies()
                    lockManager.lockNow()
                }
        }
        refreshUi()
    }

    /** Lock state badli (listener se) — agar ab lock nahi to screen band karein. */
    private fun onLockStateChanged() {
        if (store.lockState == LockState.LOCKED) {
            refreshUi()
            return
        }
        dismissLockScreen()
    }

    /**
     * Lock screen band karein — HAR path par pehle stopLockTask().
     * A-M4 pin trap: is ke baghair task pinned rehta hai aur customer
     * Setup screen mein phans jata hai (Home band rehta hai).
     */
    private fun dismissLockScreen() {
        runCatching { stopLockTask() }
            .onFailure { Log.d(TAG, "stopLockTask() on dismiss skipped/failed", it) }
        if (store.lockState == LockState.COMPLETED) {
            startActivity(Intent(this, CompletionActivity::class.java))
        }
        finish()
    }

    private fun refreshUi() {
        binding.tvLockTitle.text = getString(R.string.locked_title)
        binding.tvLockReason.text = getString(R.string.locked_reason)

        // 3.5 (F-21): OVERDUE qist ki raqam — kul baqi raqam nahi.
        val hasOverdue = store.overdueAmount >= 0
        val overdueAmount = if (hasOverdue) store.overdueAmount else store.remainingAmount
        val overdueDue = store.overdueDueDate.ifBlank { store.dueDate }
        val details = StringBuilder()
        if (overdueAmount > 0) {
            details.append("Rs $overdueAmount ${getString(R.string.lock_overdue_amount)}\n")
        }
        // PU-S9 (v2.1): "Qist #3 of 6" — server na bheje to line nahi dikhti.
        if (store.installmentNumber > 0 && store.installmentTotal > 0) {
            details.append(
                "${getString(R.string.lock_installment_number, store.installmentNumber, store.installmentTotal)}\n"
            )
        }
        if (store.overdueCount > 1) {
            details.append("${store.overdueCount} ${getString(R.string.lock_overdue_count)}\n")
        }
        if (overdueDue.isNotBlank()) {
            details.append("${getString(R.string.locked_due)}: ${DateHelper.formatIsoDate(overdueDue)}\n")
            val daysLate = DateHelper.daysOverdue(overdueDue)
            if (daysLate > 0) {
                details.append("$daysLate ${getString(R.string.lock_days_overdue)}\n")
            }
        }
        // Kul baqi raqam sirf chhoti maloomat ke tor par neeche.
        if (store.remainingAmount > 0 && store.remainingAmount != overdueAmount) {
            details.append("${getString(R.string.lock_total_remaining)}: Rs ${store.remainingAmount}\n")
        }
        binding.tvLockDetails.text = details.toString().trimEnd()

        // F-24: time context — kab se lock hai, agla check kab ho ga.
        val timeContext = StringBuilder()
        if (store.lockedSinceAt > 0) {
            timeContext.append(
                "${DateHelper.formatDateTime(store.lockedSinceAt)} ${getString(R.string.lock_locked_since)}"
            )
        }
        val nextCheck = if (store.lastCheckInAt > 0) {
            store.lastCheckInAt + CHECK_INTERVAL_MS
        } else {
            0L
        }
        if (nextCheck > 0) {
            if (timeContext.isNotEmpty()) timeContext.append("\n")
            timeContext.append("${getString(R.string.lock_next_check)}: ${DateHelper.formatTime(nextCheck)}")
        }
        binding.tvLockTime.text = timeContext.toString()
        binding.tvLockTime.visibility = if (timeContext.isEmpty()) View.GONE else View.VISIBLE

        binding.tvShopContact.text = if (store.shopPhone.isNotBlank()) {
            "${store.shopName} — ${store.shopPhone}"
        } else {
            store.shopName
        }
        binding.btnCallShop.visibility =
            if (store.shopPhone.isNotBlank()) View.VISIBLE else View.GONE
        binding.tvOfflineHint.text = getString(R.string.offline_code_hint2)
        binding.tvOfflineError.text = ""
    }

    /** F-28: payment ke baad customer khud dobara check kare. */
    private fun checkAgain() {
        if (checking) return
        checking = true
        binding.btnCheckAgain.isEnabled = false
        binding.tvCheckStatus.text = getString(R.string.lock_checking)
        Thread {
            val outcome = ServerApi(this).checkIn()
            runOnUiThread {
                checking = false
                binding.btnCheckAgain.isEnabled = true
                when (outcome) {
                    is ServerApi.CheckInOutcome.Success -> {
                        PolicyEngine.apply(this, outcome.result)
                        // Unlock hua to state listener screen khud band
                        // kar de ga; warna halat waisi hi hai.
                        if (store.lockState == LockState.LOCKED) {
                            binding.tvCheckStatus.text = getString(R.string.lock_check_ok)
                            refreshUi()
                        }
                    }
                    is ServerApi.CheckInOutcome.NetworkError -> {
                        binding.tvCheckStatus.text = when (outcome.kind) {
                            ServerApi.ErrorKind.NO_INTERNET -> getString(R.string.lock_check_no_internet)
                            ServerApi.ErrorKind.SERVER_ASLEEP -> getString(R.string.lock_check_server_slow)
                            else -> getString(R.string.lock_check_server_error)
                        }
                        PolicyEngine.recordFailure(this, PolicyEngine.statusFor(outcome.kind))
                    }
                    is ServerApi.CheckInOutcome.ServerError -> {
                        binding.tvCheckStatus.text = getString(R.string.lock_check_server_error)
                        PolicyEngine.recordFailure(
                            this,
                            PolicyEngine.CheckInStatus.serverError(outcome.error)
                        )
                    }
                }
            }
        }.start()
    }

    /** Emergency ya shop ko call — dialer lock-task allowlist mein hai. */
    private fun dialNumber(rawNumber: String) {
        val number = rawNumber.replace(" ", "").replace("-", "")
        if (number.isBlank()) return
        // Dialer allowlist mein ho (dobara yaqeeni banayein).
        lockManager.refreshLockTaskPackages()
        runCatching {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        }.onFailure {
            Toast.makeText(this, getString(R.string.call_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun tryOfflineUnlock() {
        val code = binding.etOfflineCode.text.toString().trim().uppercase()
        // F-25: khaali field ka paigham "ghalat code" se alag ho.
        if (code.isBlank()) {
            binding.tvOfflineError.text = getString(R.string.offline_code_blank)
            return
        }
        // A-M3: pehle istemal ho chuka code — saaf paigham.
        if (offlineStore.wasAlreadyUsed(code)) {
            binding.tvOfflineError.text = getString(R.string.offline_code_used)
            return
        }
        if (!offlineStore.hasCode()) {
            binding.tvOfflineError.text = getString(R.string.offline_code_none)
            return
        }
        if (!offlineStore.verifyAndBurn(code)) {
            binding.tvOfflineError.text = getString(R.string.offline_code_wrong)
            return
        }
        // Code was correct AND is now burned. Unlock locally first
        // (works fully offline), then tell the server if we can.
        // A-M3: report nakaam ho to pending rakhein — agli check-in
        // par dobara bheja jaye ga (PolicyEngine.retryPendingCodeReport).
        // PU-N7: mustaqil mana par pending saaf ho jata hai.
        offlineStore.setPendingReport(code)
        lockManager.applyUnlock()
        // PU-S8 / R-S15: "Phone khul gaya" toast SIRF tasdeeq ke baad.
        // Pehle toast foran dikhta tha, phir fresh check-in (purana
        // code hone par) phone dobara lock kar deta tha — customer ko
        // "khul gaya" dikh kar foran dobara lock nazar aata tha. Ab
        // pehle report + fresh check-in hoti hai, phir halat dekh kar
        // sahi paigham dikhta hai. Application context istemal hota
        // hai — is doran ye screen band bhi ho jaye to toast aata hai.
        val appContext = applicationContext
        Thread {
            val reportStore = OfflineCodeStore(appContext)
            when (ServerApi(appContext).reportOfflineCodeUsed(code)) {
                is ServerApi.CodeReportResult.Confirmed,
                is ServerApi.CodeReportResult.Rejected -> reportStore.clearPendingReport()
                is ServerApi.CodeReportResult.Transient -> Unit // pending rehta hai, agli check-in par retry
            }
            // Fresh check-in: server ab 24 ghante ki unlock window
            // deta hai (D-M6), is liye agli check-in par dobara lock
            // nahi hota jab tak window chal rahi ho. Agar code purana
            // tha to server lock_now de ga aur phone dobara lock ho
            // jaye ga — tab "khul gaya" nahi kehna.
            when (val outcome = ServerApi(appContext).checkIn()) {
                is ServerApi.CheckInOutcome.Success -> PolicyEngine.apply(appContext, outcome.result)
                else -> Unit // offline: cached (unlocked) state rehti hai
            }
            val stillLocked = LockStateStore(appContext).lockState == LockState.LOCKED
            Handler(Looper.getMainLooper()).post {
                val msg = if (stillLocked) {
                    appContext.getString(R.string.offline_unlock_relocked)
                } else {
                    appContext.getString(R.string.offline_unlock_done)
                }
                Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
        dismissLockScreen()
    }

    companion object {
        private const val TAG = "LockActivity"
        private const val EMERGENCY_NUMBER = "1122"
        private const val CHECK_INTERVAL_MS = 15L * 60L * 1000L
    }
}
