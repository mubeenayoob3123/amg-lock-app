package pk.amgsystems.lock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin receiver.
 *
 * This class is what Android uses to make the app a Device Owner
 * during QR provisioning (see QR-PROVISIONING.md). Without it,
 * Device Owner provisioning is impossible.
 *
 * onProfileProvisioningComplete: provisioning finished -> the phone
 * is now managed by AMG Lock. We immediately:
 *   1. Apply the anti-tamper rules (LockManager.applyDeviceOwnerPolicies)
 *   2. Open MainActivity so the shop can enter the pairing code.
 */
class AmgDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Provisioning complete — applying Device Owner policies")
        LockManager(context).applyDeviceOwnerPolicies()
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launch)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled")
    }

    companion object {
        private const val TAG = "AmgDeviceAdmin"
    }
}
