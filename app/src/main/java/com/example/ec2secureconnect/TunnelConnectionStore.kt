package com.example.ec2secureconnect

import android.content.Context
import android.content.Intent
import androidx.core.content.edit

enum class TunnelStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR;

    companion object {
        fun from(raw: String?): TunnelStatus =
            entries.firstOrNull { it.name == raw } ?: DISCONNECTED
    }
}

data class TunnelConnectionState(
    val activeProfileId: String?,
    val lastProfileId: String?,
    val status: TunnelStatus,
    val message: String?
)

object TunnelConnectionStore {

    const val ACTION_STATE_CHANGED = "com.example.android_ssm.ACTION_TUNNEL_STATE_CHANGED"
    private const val PREFS_NAME = "ssm_tunnel_state"
    private const val KEY_PROFILE_ID = "active_profile_id"
    private const val KEY_LAST_PROFILE_ID = "last_profile_id"
    private const val KEY_STATUS = "status"
    private const val KEY_MESSAGE = "message"

    fun load(context: Context): TunnelConnectionState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return TunnelConnectionState(
            activeProfileId = prefs.getString(KEY_PROFILE_ID, null),
            lastProfileId = prefs.getString(KEY_LAST_PROFILE_ID, null),
            status = TunnelStatus.from(prefs.getString(KEY_STATUS, TunnelStatus.DISCONNECTED.name)),
            message = prefs.getString(KEY_MESSAGE, null)
        )
    }

    fun markConnecting(context: Context, profileId: String) {
        update(context, profileId, profileId, TunnelStatus.CONNECTING, null)
    }

    fun markConnected(context: Context, profileId: String) {
        update(context, profileId, profileId, TunnelStatus.CONNECTED, null)
    }

    fun markDisconnected(context: Context, profileId: String?) {
        update(context, null, profileId, TunnelStatus.DISCONNECTED, null)
    }

    fun markError(context: Context, profileId: String, message: String) {
        update(context, null, profileId, TunnelStatus.ERROR, message)
    }

    private fun update(
        context: Context,
        activeProfileId: String?,
        lastProfileId: String?,
        status: TunnelStatus,
        message: String?
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_PROFILE_ID, activeProfileId)
            putString(KEY_LAST_PROFILE_ID, lastProfileId)
            putString(KEY_STATUS, status.name)
            putString(KEY_MESSAGE, message)
        }
        context.sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(context.packageName))
    }
}
