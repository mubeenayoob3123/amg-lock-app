package pk.amgsystems.lock

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import pk.amgsystems.lock.databinding.ActivityCompletionBinding

/**
 * Completion screen (F-32 / 3.9) — aakhri qist ada ho gayi, phone ab
 * poora customer ka hai.
 *
 * Dikhata hai: "Mubarak! Phone Aap Ka Hai", tasdeeq ke Device Owner
 * hat gaya hai ya nahi, aur uninstall ka button.
 *
 * A-M7 fix: uninstall kabhi background se khamoshi se nahi hota.
 * Yahan (foreground) prompt khulta hai aur us ka nateeja dikhaya
 * jata hai — agar prompt na khule to seedhi hidayat milti hai
 * (Setting > Apps > AMG Lock > Uninstall), kabhi khamosh nakaami nahi.
 * Registration state tab tak nahi mitayi jati jab tak uninstall
 * waqai na ho jaye (uninstall hone par Android khud sab mita deta hai).
 *
 * J1-m70: dobara enroll ka rasta bhi yahin hai — phone dobara qist
 * par bechna ho (exchange / wapas le kar dobara sale) to registration
 * saaf karke dobara register kiya ja sakta hai. NOTE: dobara register
 * ke liye phone ko QR se dobara Device Owner banana parta hai
 * (completion par Device Owner hat chuka hota hai).
 */
class CompletionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompletionBinding
    private lateinit var lockManager: LockManager
    private lateinit var store: LockStateStore
    private var uninstallPrompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompletionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lockManager = LockManager(this)
        store = LockStateStore(this)

        // J1-p15: screen khul gayi — completion notification ab zaroorat nahi.
        NotificationHelper.cancelCompletion(this)

        binding.tvDoneTitle.text = getString(R.string.completed_title)
        binding.tvDoneBody.text = getString(R.string.completed_body)
        binding.tvDoneNote.text = getString(R.string.completed_note)

        binding.btnUninstall.setOnClickListener { tryUninstall() }
        binding.btnRetryOwner.setOnClickListener { retryClearOwner() }
        binding.btnReEnroll.setOnClickListener { confirmReEnroll() }
    }

    override fun onResume() {
        super.onResume()
        refreshVerification()
    }

    /** Device Owner hata ya nahi — tasdeeq dikhayein (kabhi andaza nahi). */
    private fun refreshVerification() {
        val isOwner = lockManager.isDeviceOwner()
        if (isOwner) {
            binding.tvVerifyOwner.text =
                "${getString(R.string.completed_verify_owner)}: ${getString(R.string.completed_verify_no)}"
            binding.btnRetryOwner.visibility = View.VISIBLE
            binding.tvUninstallStatus.text = getString(R.string.completed_owner_still_active)
            binding.btnUninstall.isEnabled = false
        } else {
            binding.tvVerifyOwner.text =
                "${getString(R.string.completed_verify_owner)}: ${getString(R.string.completed_verify_yes)}"
            binding.btnRetryOwner.visibility = View.GONE
            binding.btnUninstall.isEnabled = true
            if (!uninstallPrompted) {
                binding.tvUninstallStatus.text = ""
            }
        }
    }

    private fun retryClearOwner() {
        binding.tvUninstallStatus.text = getString(R.string.completed_retrying)
        Thread {
            val cleared = lockManager.clearDeviceOwnerAndRestrictions()
            runOnUiThread {
                refreshVerification()
                if (!cleared) {
                    binding.tvUninstallStatus.text = getString(R.string.completed_owner_still_active)
                }
            }
        }.start()
    }

    private fun tryUninstall() {
        val launched = lockManager.tryUninstallSelf()
        uninstallPrompted = true
        binding.tvUninstallStatus.text = if (launched) {
            getString(R.string.completed_uninstall_launched)
        } else {
            getString(R.string.completed_uninstall_failed)
        }
    }

    /**
     * Dobara enroll (J1-m70): phone dobara qist par bechna ho to
     * registration saaf karke Setup par le jayein. Tasdeeq ke saath —
     * aur saaf lafzon mein batayein ke dobara register ke liye phone
     * ko QR se dobara Device Owner banana pare ga.
     */
    private fun confirmReEnroll() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.completed_reenroll_title))
            .setMessage(getString(R.string.completed_reenroll_message))
            .setPositiveButton(getString(R.string.completed_reenroll_confirm)) { _, _ -> reEnroll() }
            .setNegativeButton(getString(R.string.reset_cancel), null)
            .show()
    }

    private fun reEnroll() {
        NotificationHelper.cancelWarning(this)
        NotificationHelper.cancelCompletion(this)
        OfflineCodeStore(this).clearAll()
        store.clearAll()
        BootState.sync(this)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }
}
