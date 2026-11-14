package pk.amgsystems.lock

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import pk.amgsystems.lock.databinding.ActivityWarningBinding

/**
 * Warning screen (F-19 / 3.4) — qist late hai, grace period chal raha
 * hai, phone abhi poora chalta hai.
 *
 * Dikhata hai: is qist ki raqam, due tareekh, grace mein kitne din
 * baqi hain, kis tareekh ko phone lock ho jaye ga, shop ka number
 * (dabane par call), aur "qist ada karein" ki hidayat.
 *
 * Ye screen tab khulti hai jab server pehli dafa "warn" kehta hai
 * (PolicyEngine), ya jab customer warning notification dabata hai,
 * ya MainActivity ke warning banner se.
 */
class WarningActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWarningBinding
    private lateinit var store: LockStateStore
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWarningBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = LockStateStore(this)

        binding.btnWarnDismiss.setOnClickListener { finish() }
        binding.btnWarnCallShop.setOnClickListener { callShop() }
        binding.btnWarnCheckNow.setOnClickListener { checkNow() }
    }

    override fun onResume() {
        super.onResume()
        // Halat badal gayi ho (payment aa gayi / lock ho gaya) to ye
        // screen khud band ho jaye.
        when (store.lockState) {
            LockState.LOCKED -> {
                startActivity(Intent(this, LockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
                finish()
                return
            }
            LockState.COMPLETED -> {
                startActivity(Intent(this, CompletionActivity::class.java))
                finish()
                return
            }
            LockState.ACTIVE -> {
                finish()
                return
            }
            else -> Unit
        }
        refreshUi()
    }

    private fun refreshUi() {
        binding.tvWarnTitle.text = getString(R.string.warning_title)

        // Is qist ki raqam: monthlyAmount (agli qist) pehle, phir
        // overdueAmount, aakhir mein kul baqi raqam (fallback).
        val amount = when {
            store.monthlyAmount > 0 -> store.monthlyAmount
            store.overdueAmount > 0 -> store.overdueAmount
            else -> store.remainingAmount
        }
        val dueDate = store.overdueDueDate.ifBlank { store.nextDueDate.ifBlank { store.dueDate } }
        val details = StringBuilder()
        if (amount > 0) {
            details.append("${getString(R.string.warning_amount_due)}: Rs $amount\n")
        }
        if (dueDate.isNotBlank()) {
            details.append("${getString(R.string.locked_due)}: ${DateHelper.formatIsoDate(dueDate)}\n")
            val daysLate = DateHelper.daysOverdue(dueDate)
            if (daysLate > 0) {
                details.append("$daysLate ${getString(R.string.lock_days_overdue)}\n")
            }
        }
        if (store.graceDaysLeft > 0) {
            details.append(
                "${getString(R.string.grace_label)}: ${store.graceDaysLeft} " +
                    "${getString(R.string.warning_days_left)}\n"
            )
            val lockDate = DateHelper.datePlusDaysFormatted(store.graceDaysLeft)
            if (lockDate.isNotBlank()) {
                details.append("${getString(R.string.warning_lock_date)}: $lockDate\n")
            }
        }
        if (store.remainingAmount > 0 && store.remainingAmount != amount) {
            details.append("${getString(R.string.lock_total_remaining)}: Rs ${store.remainingAmount}\n")
        }
        binding.tvWarnDetails.text = details.toString().trimEnd()
        binding.tvWarnGuidance.text = getString(R.string.warning_pay_guidance)

        if (store.shopPhone.isNotBlank()) {
            binding.btnWarnCallShop.visibility = View.VISIBLE
            binding.btnWarnCallShop.text =
                "${getString(R.string.call_shop_button)} (${store.shopPhone})"
        } else {
            binding.btnWarnCallShop.visibility = View.GONE
        }
        binding.tvWarnShop.text = if (store.shopName.isNotBlank()) store.shopName else ""
    }

    private fun callShop() {
        val phone = store.shopPhone.replace(" ", "").replace("-", "")
        if (phone.isBlank()) return
        runCatching {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        }.onFailure {
            Toast.makeText(this, getString(R.string.call_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun checkNow() {
        if (checking) return
        checking = true
        binding.btnWarnCheckNow.isEnabled = false
        binding.tvWarnStatus.text = getString(R.string.lock_checking)
        Thread {
            val outcome = ServerApi(this).checkIn()
            runOnUiThread {
                checking = false
                binding.btnWarnCheckNow.isEnabled = true
                when (outcome) {
                    is ServerApi.CheckInOutcome.Success -> {
                        PolicyEngine.apply(this, outcome.result)
                        if (outcome.result.instruction == "stay_unlocked") {
                            Toast.makeText(this, getString(R.string.check_ok_unlocked), Toast.LENGTH_LONG).show()
                            finish()
                        } else {
                            binding.tvWarnStatus.text = getString(R.string.lock_check_ok)
                            refreshUi()
                        }
                    }
                    is ServerApi.CheckInOutcome.NetworkError -> {
                        binding.tvWarnStatus.text = when (outcome.kind) {
                            ServerApi.ErrorKind.NO_INTERNET -> getString(R.string.lock_check_no_internet)
                            ServerApi.ErrorKind.SERVER_ASLEEP -> getString(R.string.lock_check_server_slow)
                            else -> getString(R.string.lock_check_server_error)
                        }
                        PolicyEngine.recordFailure(this, PolicyEngine.statusFor(outcome.kind))
                    }
                    is ServerApi.CheckInOutcome.ServerError -> {
                        binding.tvWarnStatus.text = getString(R.string.lock_check_server_error)
                        PolicyEngine.recordFailure(
                            this,
                            PolicyEngine.CheckInStatus.serverError(outcome.error)
                        )
                    }
                }
            }
        }.start()
    }
}
