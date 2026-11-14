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
 *   1. Apply the admin extras from the provisioning QR, if the shop
 *      put any in (server_url / pairing_code — QrProvisioning), so
 *      Setup opens pre-filled instead of blank.
 *   2. Apply the anti-tamper rules (LockManager.applyDeviceOwnerPolicies)
 *   3. Open MainActivity so the shop can register (or just tap Register
 *      if the extras already filled everything in).
 */
class AmgDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Provisioning complete — applying admin extras + Device Owner policies")
        // Wave 5.6: provisioning QR ke admin extras (server_url /
        // pairing_code) store mein likhein taake Setup pre-filled khule.
        QrProvisioning.applyProvisioningExtras(context, intent)
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
