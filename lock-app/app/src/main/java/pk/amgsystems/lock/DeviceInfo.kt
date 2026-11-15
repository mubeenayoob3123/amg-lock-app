package pk.amgsystems.lock

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

/**
 * Phone identity for registration.
 *
 * IMEI is NOT available on modern Android without special carrier
 * permissions, so we do NOT ask for it. We use ANDROID_ID (unique per
 * app-signing-key + user) as the stable device identifier, plus the
 * human-readable model name. The shop records the real IMEI separately
 * in the dashboard's Stock screen — the two are linked by the account,
 * not by this value.
 *
 * J1-m62: is value ko UI mein kabhi "IMEI" NAHI kehte — ye ANDROID_ID
 * hai (factory reset par badal jati hai, IMEI ki tarah mustaqil nahi).
 * Server ko bhejne wale JSON field ka naam ("imei") purana contract
 * hai, is liye wire par wahi rehta hai — sirf naam badla hai, field nahi.
 */
object DeviceInfo {

    /** Phone ki ANDROID_ID (UI mein "Device ID" ke naam se dikhani hai). */
    @SuppressLint("HardwareIds")
    fun androidId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
    }
}
