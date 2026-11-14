package pk.amgsystems.lock

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.UserManager
import android.util.Log

/**
 * Everything that actually locks / unlocks the phone.
 *
 * All Device Owner powers live here and NOWHERE else, so the rules
 * are easy to audit:
 *
 *  - applyDeviceOwnerPolicies(): called after QR provisioning, and
 *    re-applied on every MainActivity launch (the adb
 *    `set-device-owner` test path never fires the provisioning
 *    callback, so without this the policies would never run there).
 *      * While locked, Home / Recents cannot escape: lock task mode
 *        pins the phone to LockActivity. (The app is NOT set as the
 *        default launcher — no addPersistentPreferredActivity; the
 *        pin is what holds the lock, not a launcher role.)
 *      * Uninstall blocked, factory reset blocked (Settings + recovery
 *        entry point), status bar disabled while locked, screen
 *        capture disabled, adding users / unknown sources blocked.
 *  - applyLock(): show LockActivity + start lock task mode (pinned).
 *  - applyUnlock(): stop lock task, close LockActivity, normal phone.
 *  - applyPermanentUnlock(): final payment. Clears the Device Owner
 *    role and all restrictions, then the COMPLETION screen (foreground)
 *    verifies the removal and offers the uninstall — never a silent
 *    background uninstall (A-M7 / F-32).
 *
 * HONEST LIMITS (Android rules, not app bugs — also in README):
 *  - Safe Mode: Android does NOT let any app block booting into Safe
 *    Mode. In Safe Mode third-party apps (including this one) do not
 *    run, so the lock screen is not shown — BUT the Device Owner
 *    role survives Safe Mode, and on the next normal boot the lock
 *    comes straight back. A forced factory reset is blocked from
 *    Settings; a reset from recovery still wipes the phone — then
 *    Factory Reset Protection (FRP) + the shop's written agreement
 *    are the protection, and the phone re-enrolls on setup.
 *  - An expert who flashes firmware can bypass any software lock.
 *    This stops ordinary customers, not a determined technician.
 */
class LockManager(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, AmgDeviceAdminReceiver::class.java)
    private val store = LockStateStore(context)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    fun isAdminActive(): Boolean = dpm.isAdminActive(admin)

    /**
     * Setup after QR provisioning, re-applied on every MainActivity
     * launch so the adb set-device-owner path gets the same policies.
     * Safe to call repeatedly — every call below is idempotent.
     * No-op (with a log) if not Device Owner.
     */
    fun applyDeviceOwnerPolicies() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not Device Owner — policies NOT applied (normal install)")
            return
        }
        // The app cannot be uninstalled while it is Device Owner.
        dpm.setUninstallBlocked(admin, context.packageName, true)
        // Factory reset from Settings is blocked.
        dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        // No second user, no unknown-source APKs, no new accounts to escape with.
        dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
        dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)
        // A-M6: customer ka apna screen lock (PIN / pattern / fingerprint)
        // band NAHI karte. LockActivity showWhenLocked / turnScreenOn se
        // keyguard ke oopar dikhti hai, is liye keyguard band karne ki
        // zaroorat nahi — aur band keyguard se emergency dialer bhi
        // nahi khulta. Purani install par band hua keyguard dobara on
        // kar dein (false = keyguard dobara chalu).
        dpm.setKeyguardDisabled(admin, false)
        dpm.setScreenCaptureDisabled(admin, true)
        // This app is the only lock-task package (+ dialer, taake lock
        // screen se emergency / shop call ho sake — F-22).
        refreshLockTaskPackages()
        Log.i(TAG, "Device Owner policies applied")
    }

    /**
     * Lock-task allowlist: hamari app + phone ka dialer. Dialer ke
     * baghair lock screen se koi call nahi ho sakti (lock task mein
     * sirf allowlist wali apps khulti hain). Dialer ka package har
     * company ka alag hota hai, is liye resolve kar ke nikaalte hain.
     */
    fun refreshLockTaskPackages() {
        if (!isDeviceOwner()) return
        val packages = mutableSetOf(context.packageName)
        runCatching {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:1122"))
            val resolved = context.packageManager.resolveActivity(
                dialIntent, PackageManager.MATCH_DEFAULT_ONLY
            )
            resolved?.activityInfo?.packageName?.let { packages.add(it) }
        }.onFailure { Log.w(TAG, "Dialer resolve nahi hua — lock-task mein sirf ye app", it) }
        runCatching { dpm.setLockTaskPackages(admin, packages.toTypedArray()) }
            .onFailure { Log.e(TAG, "setLockTaskPackages failed", it) }
    }

    /** Show the lock screen and pin the phone to it. */
    fun applyLock() {
        store.lockState = LockState.LOCKED
        if (isDeviceOwner()) {
            dpm.setStatusBarDisabled(admin, true)
        }
        val intent = Intent(context, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        // NOTE (A-M12): this only means the lock screen was launched.
        // Whether the phone is actually pinned is decided in
        // LockActivity.onResume — see the failure log + lockNow()
        // fallback there. Do not log "Phone LOCKED" here: a failed pin
        // would look identical to a successful lock.
        Log.i(TAG, "Lock screen launched (pinning happens in LockActivity)")
    }

    /**
     * Fallback when lock-task pinning fails (A-M12): lock the device
     * screen immediately via DevicePolicyManager. The lock screen
     * (LockActivity, showWhenLocked) is what the customer sees when
     * the phone wakes, so the phone is still protected even though
     * it is not pinned. No-op (with a log) if not Device Owner.
     */
    fun lockNow() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "lockNow() fallback skipped — not Device Owner")
            return
        }
        runCatching { dpm.lockNow() }
            .onFailure { Log.e(TAG, "lockNow() fallback also FAILED", it) }
            .onSuccess { Log.i(TAG, "lockNow() fallback applied (pin failed)") }
    }

    /**
     * Normal unlock (payment received / offline code used).
     * A-M4 (pin trap fix): stopLockTask() har unlock path par chalta
     * hai — warna LockActivity band hone ke baad bhi task pinned rehta
     * hai aur customer Setup screen mein phans jata hai. Yahan sirf
     * tab chalta hai jab context aik Activity ho; LockActivity khud
     * bhi apne dismiss path par stopLockTask() karti hai, aur
     * MainActivity onResume par hifazati tor par dobara karti hai.
     */
    fun applyUnlock() {
        store.lockState = LockState.ACTIVE
        if (isDeviceOwner()) {
            dpm.setStatusBarDisabled(admin, false)
        }
        stopLockTaskIfPossible()
        // LockActivity (if open) finishes itself when it sees ACTIVE.
        Log.i(TAG, "Phone UNLOCKED")
    }

    /** Activity context mile to lock task rok dein (na ho to khamoshi se chhor dein). */
    fun stopLockTaskIfPossible() {
        val activity = context as? Activity ?: return
        runCatching { activity.stopLockTask() }
            .onFailure { Log.d(TAG, "stopLockTask() skipped/failed (shayad lock task on hi nahi tha)", it) }
    }

    /** Warning state: phone still usable, warning banner is shown. */
    fun applyWarning() {
        store.lockState = LockState.WARNING
        Log.i(TAG, "WARNING shown (grace period running)")
    }

    /**
     * Final payment: permanent unlock.
     * Order matters:
     *  1. Remove every restriction + clear the Device Owner role.
     *  2. State COMPLETED rakhein — state tab tak NAHI mitate jab tak
     *     uninstall waqai na ho jaye (A-M7: pehle state mit jati thi
     *     aur aik bekaar app reh jati thi).
     *  3. Completion screen + notification: uninstall sirf foreground
     *     CompletionActivity se hota hai, aur us ki tasdeeq hoti hai.
     *     Background se ACTION_DELETE Android 10+ par band hai.
     */
    fun applyPermanentUnlock() {
        val cleared = clearDeviceOwnerAndRestrictions()
        store.lockState = LockState.COMPLETED
        Log.i(TAG, "Permanent unlock — Device Owner cleared: $cleared")
        // Completion screen kholne ki koshish (foreground se chalta hai;
        // background worker se Android rok deta hai — notification fallback hai).
        NotificationHelper.showCompletion(context)
        runCatching {
            val intent = Intent(context, CompletionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            Log.w(TAG, "Could not launch CompletionActivity from background (notification is the fallback)", it)
        }
    }

    /**
     * Tamam pabandiyan hatayein + Device Owner role khatam karein.
     * Returns true agar is ke baad app Device Owner NAHI rahi
     * (ya pehle se hi nahi thi).
     */
    fun clearDeviceOwnerAndRestrictions(): Boolean {
        if (!isDeviceOwner()) return true
        runCatching { dpm.setStatusBarDisabled(admin, false) }
        runCatching { dpm.setUninstallBlocked(admin, context.packageName, false) }
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET) }
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER) }
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) }
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING) }
        runCatching { dpm.setKeyguardDisabled(admin, false) }
        runCatching { dpm.setScreenCaptureDisabled(admin, false) }
        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
        runCatching { dpm.clearDeviceOwnerApp(context.packageName) }
        val cleared = !isDeviceOwner()
        Log.i(TAG, "Device Owner role cleared: $cleared")
        return cleared
    }

    /**
     * Khud ko uninstall karne ka system prompt kholein.
     * Sirf foreground CompletionActivity se call karein.
     * Returns true agar prompt waqai khul gaya, false agar Android
     * ne rok diya (tab UI mein seedhi hidayat dikhayi jati hai).
     * State yahan NAHI mitate — agar customer prompt band kar de to
     * completion screen dobara khulne par wahi halat dikhe.
     */
    fun tryUninstallSelf(): Boolean {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }
            .onSuccess { Log.i(TAG, "Uninstall prompt launched") }
            .onFailure { Log.e(TAG, "Uninstall prompt FAILED to launch", it) }
            .isSuccess
    }

    companion object {
        private const val TAG = "LockManager"
    }
}
