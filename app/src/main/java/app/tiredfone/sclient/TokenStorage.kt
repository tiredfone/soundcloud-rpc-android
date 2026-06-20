package app.tiredfone.sclient

import android.content.Context

class TokenStorage(context: Context) {
    private val prefs = context.getSharedPreferences("rpc_prefs", Context.MODE_PRIVATE)

    var discordToken: String?
        get() = prefs.getString("discord_token", null)
        set(value) = prefs.edit().putString("discord_token", value).apply()

    var applicationId: String?
        get() = prefs.getString("application_id", null)
        set(value) = prefs.edit().putString("application_id", value).apply()

    var rpcEnabled: Boolean
        get() = prefs.getBoolean("rpc_enabled", true)
        set(value) = prefs.edit().putBoolean("rpc_enabled", value).apply()

    fun isConfigured(): Boolean = !discordToken.isNullOrBlank() && !applicationId.isNullOrBlank()

    var soundcloudToken: String?
        get() = prefs.getString("sc_token", null)
        set(value) = prefs.edit().putString("sc_token", value).apply()

    var soundcloudClientId: String?
        get() = prefs.getString("sc_client_id", null)
        set(value) = prefs.edit().putString("sc_client_id", value).apply()

    var soundcloudRefreshToken: String?
        get() = prefs.getString("sc_refresh_token", null)
        set(value) = prefs.edit().putString("sc_refresh_token", value).apply()

    var soundcloudUserId: Long
        get() = prefs.getLong("sc_user_id", 0L)
        set(value) = prefs.edit().putLong("sc_user_id", value).apply()

    fun isSoundCloudLoggedIn(): Boolean = !soundcloudToken.isNullOrBlank()
}
