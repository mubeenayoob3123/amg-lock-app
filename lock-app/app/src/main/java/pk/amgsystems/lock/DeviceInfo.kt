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
 */
object DeviceInfo {

    @SuppressLint("HardwareIds")
    fun imei(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
    }
}
