package pk.amgsystems.lock

import android.os.Bundle
import android.view.View
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
 */
class CompletionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompletionBinding
    private lateinit var lockManager: LockManager
    private var uninstallPrompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompletionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lockManager = LockManager(this)

        binding.tvDoneTitle.text = getString(R.string.completed_title)
        binding.tvDoneBody.text = getString(R.string.completed_body)
        binding.tvDoneNote.text = getString(R.string.completed_note)

        binding.btnUninstall.setOnClickListener { tryUninstall() }
        binding.btnRetryOwner.setOnClickListener { retryClearOwner() }
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
}
