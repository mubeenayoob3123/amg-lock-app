package pk.amgsystems.lock

import android.app.Activity
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.UserManager
import android.util.Log
import androidx.work.WorkManager

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
 *  - Safe Mode (v1.4.0 se BLOCKED — J2-M16): Device Owner restriction
 *    DISALLOW_SAFE_BOOT Safe Mode boot rokta hai. (Pehle docs mein
 *    ghalat likha tha ke Android Safe Mode block nahi karne deta —
 *    ye restriction Device Owner ke liye maujood hai.) Kuch OEM
 *    hardware-key boot path par isay na bhi manein to Device Owner
 *    role Safe Mode mein bhi rehta hai aur normal boot par lock
 *    wapas aa jata hai — test phone par verify karein.
 *  - USB debugging (v1.4.0 se BLOCKED — J2-M16):
 *    DISALLOW_DEBUGGING_FEATURES adb samait debugging band kar deta
 *    hai. NOTE: policies lagne ke baad adb bhi band ho jata hai —
 *    ye hifazat hai, bug nahi (adb test path sirf setup ke liye hai).
 *  - A forced factory reset from recovery still wipes the phone —
 *    then Factory Reset Protection (FRP) + the shop's written
 *    agreement are the protection, and the phone re-enrolls on setup.
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
        // J2-M16: Safe Mode boot + USB debugging bhi block — dono Device
        // Owner restriction hain (pehle docs mein ghalat likha tha ke
        // Safe Mode block nahi ho sakta). NOTE: is ke baad adb bhi band
        // ho jata hai — test ke liye adb sirf policies lagne se pehle
        // istemal ho sakta hai (setup ke waqt).
        dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
        dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
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
        BootState.sync(context)
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
     * Local deadline lock (J2-M14): phone khud offline lock lagata hai
     * (aakhri sync wali deadline guzar gayi). Server lock jaisa hi hai,
     * farq sirf itna hai ke lock screen offline wala paigham dikhati
     * hai (localLock flag) — aur agli kamyab check-in (stay_unlocked /
     * warn) isay foran khol deti hai.
     */
    fun applyLocalDeadlineLock() {
        if (store.lockState == LockState.LOCKED) {
            store.localLock = true
            return
        }
        store.lockedSinceAt = System.currentTimeMillis()
        applyLock()
        store.localLock = true
        Log.w(TAG, "LOCAL deadline lock applied (offline past due + grace + 48h buffer)")
    }

    /**
     * Fallback when lock-task pinning fails (A-M12): lock the device
     * screen immediately via DevicePolicyManager. The lock screen
     * (LockActivity, showWhenLocked) is what the customer sees when
     * the phone wakes, so the phone is still protected even though
     * it is not pinned. No-op (with a log) if not Device Owner.
     *
     * J2-m31: pehle check karein ke keyguard mehfooz hai (PIN /
     * pattern / fingerprint set hai) — baghair secure keyguard ke
     * lockNow ka koi faida nahi (phone jagte hi khul jata hai, Home
     * band nahi hota). Keyguard mehfooz na ho to lock-task mode
     * dobara lagane ki koshish karein (activity mile to), warna
     * zor se log karein ke phone mehfooz NAHI hai.
     */
    fun lockNow(activity: Activity? = null) {
        if (!isDeviceOwner()) {
            Log.w(TAG, "lockNow() fallback skipped — not Device Owner")
            return
        }
        val keyguardManager =
            context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguardManager.isKeyguardSecure) {
            Log.w(TAG, "lockNow() skipped — keyguard is NOT secure, falling back to lock-task mode")
            if (activity != null) {
                runCatching { activity.startLockTask() }
                    .onFailure { Log.e(TAG, "lock-task fallback ALSO failed — phone is NOT protected (keyguard not secure)", it) }
                    .onSuccess { Log.i(TAG, "lock-task fallback applied (keyguard not secure)") }
            } else {
                Log.e(TAG, "No activity for lock-task fallback — phone is NOT protected (keyguard not secure)")
            }
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
        store.localLock = false
        if (isDeviceOwner()) {
            dpm.setStatusBarDisabled(admin, false)
        }
        stopLockTaskIfPossible()
        BootState.sync(context)
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
        store.localLock = false
        BootState.sync(context)
        Log.i(TAG, "WARNING shown (grace period running)")
    }

    /**
     * Final payment: permanent unlock.
     * Order matters:
     *  1. Remove every restriction + clear the Device Owner role.
     *  2. State COMPLETED SIRF tab rakhein jab Device Owner hatne ki
     *     TASDEEQ ho jaye (J2-m35 — pehle tasdeeq ke baghair COMPLETED
     *     likha jata tha, phir worker band ho jata tha aur retry ka
     *     koi rasta nahi rehta tha). Tasdeeq na ho to phone khul to
     *     jata hai (qist poori ada hai) lekin completionPending lag
     *     jata hai — agli check-in dobara koshish kare gi, aur
     *     MainActivity mein retry button dikhta hai.
     *  3. Completion screen + notification: uninstall sirf foreground
     *     CompletionActivity se hota hai, aur us ki tasdeeq hoti hai.
     *     Background se ACTION_DELETE Android 10+ par band hai.
     *  4. COMPLETED hone par background workers band ho jate hain
     *     (J1-p15 — 15-minute worker completion ke baad hamesha
     *     chalta rehta tha).
     */
    fun applyPermanentUnlock() {
        val cleared = clearDeviceOwnerAndRestrictions()
        if (!cleared) {
            // Device Owner abhi hata nahi — COMPLETED NAHI likhte.
            // Phone phir bhi khul jata hai (aakhri qist ada ho gayi),
            // lekin completion adhoori rehti hai (retry agli check-in par).
            applyUnlock()
            store.completionPending = true
            NotificationHelper.showCompletionPending(context)
            Log.w(TAG, "Permanent unlock: Device Owner NOT cleared — completion pending, will retry on next check-in")
            return
        }
        store.completionPending = false
        store.lockState = LockState.COMPLETED
        store.localLock = false
        Log.i(TAG, "Permanent unlock — Device Owner cleared: $cleared")
        // J1-p15: completion ke baad background check-in band.
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(CheckInWorker.WORK_NAME) }
        DeadlineCheckWorker.cancel(context)
        NotificationHelper.cancelCompletionPending(context)
        BootState.sync(context)
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
     * Completion dobara koshish karein (J2-m35 — MainActivity ke
     * pending card se). Returns true agar is dafa Device Owner hat
     * gaya ho (tab COMPLETED ho jata hai).
     */
    fun retryCompletion(): Boolean {
        val cleared = clearDeviceOwnerAndRestrictions()
        if (cleared) {
            applyPermanentUnlock()
        }
        return cleared
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
        // J2-M16 wali pabandiyan bhi hatayein (completion par phone aazaad hai).
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT) }
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES) }
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
