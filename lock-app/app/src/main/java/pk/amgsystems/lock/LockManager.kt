package pk.amgsystems.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
 *      * This app becomes the launcher/home (customer cannot leave it
 *        by pressing Home while locked — lock task mode handles that).
 *      * Uninstall blocked, factory reset blocked (Settings + recovery
 *        entry point), status bar disabled while locked, screen
 *        capture disabled, adding users / unknown sources blocked.
 *  - applyLock(): show LockActivity + start lock task mode (pinned).
 *  - applyUnlock(): stop lock task, close LockActivity, normal phone.
 *  - applyPermanentUnlock(): final payment. Clears the Device Owner
 *    role, removes all restrictions, then the app uninstalls itself.
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
        // Lock screen must be usable: keyguard features off, no screenshots.
        dpm.setKeyguardDisabled(admin, true)
        dpm.setScreenCaptureDisabled(admin, true)
        // This app is the only lock-task package: Home/Recents cannot escape it.
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        Log.i(TAG, "Device Owner policies applied")
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

    /** Normal unlock (payment received / offline code used). */
    fun applyUnlock() {
        store.lockState = LockState.ACTIVE
        if (isDeviceOwner()) {
            dpm.setStatusBarDisabled(admin, false)
        }
        // LockActivity (if open) finishes itself when it sees ACTIVE.
        Log.i(TAG, "Phone UNLOCKED")
    }

    /** Warning state: phone still usable, warning banner is shown. */
    fun applyWarning() {
        store.lockState = LockState.WARNING
        Log.i(TAG, "WARNING shown (grace period running)")
    }

    /**
     * Final payment: permanent unlock.
     * Order matters:
     *  1. Remove every restriction + stop lock task.
     *  2. Clear the Device Owner role (this is the point of no return).
     *  3. Uninstall ourselves — the customer now owns the phone fully.
     * If step 3 fails (e.g. no installer permission), the app stays
     * installed but harmless: Device Owner is already gone, so the
     * customer can uninstall it like any normal app.
     */
    fun applyPermanentUnlock() {
        store.lockState = LockState.COMPLETED
        if (isDeviceOwner()) {
            runCatching { dpm.setStatusBarDisabled(admin, false) }
            runCatching { dpm.setUninstallBlocked(admin, context.packageName, false) }
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET) }
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER) }
            runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
            runCatching { dpm.clearDeviceOwnerApp(context.packageName) }
            Log.i(TAG, "Device Owner role cleared — permanent unlock")
        }
        uninstallSelf()
    }

    private fun uninstallSelf() {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
        // Fallback: if the uninstall prompt never appears, at least
        // wipe our stored state so nothing lock-related survives.
        store.clearAll()
        OfflineCodeStore(context).clear()
    }

    companion object {
        private const val TAG = "LockManager"
    }
}
