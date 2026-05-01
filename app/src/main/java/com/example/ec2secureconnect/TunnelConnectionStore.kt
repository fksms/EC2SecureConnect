package com.example.ec2secureconnect

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

enum class TunnelStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR;
}

data class TunnelStatusInfo(
    val status: TunnelStatus, val message: String? = null
)

data class TunnelConnectionState(
    val profileStates: Map<String, TunnelStatusInfo>
)

object TunnelConnectionStore {

    const val ACTION_STATE_CHANGED = "com.example.android_ssm.ACTION_TUNNEL_STATE_CHANGED"
    private const val PREFS_NAME = "ssm_tunnel_state_v2"
    private const val KEY_STATES = "profile_states"

    private val gson = Gson()

    fun load(context: Context): TunnelConnectionState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_STATES, null)
        val profileStates = if (json != null) {
            val type = object : TypeToken<Map<String, TunnelStatusInfo>>() {}.type
            gson.fromJson<Map<String, TunnelStatusInfo>>(json, type) ?: emptyMap()
        } else {
            emptyMap()
        }
        return TunnelConnectionState(profileStates)
    }

    fun markConnecting(context: Context, profileId: String) {
        update(context, profileId, TunnelStatusInfo(TunnelStatus.CONNECTING))
    }

    fun markConnected(context: Context, profileId: String) {
        update(context, profileId, TunnelStatusInfo(TunnelStatus.CONNECTED))
    }

    fun markDisconnected(context: Context, profileId: String) {
        update(context, profileId, TunnelStatusInfo(TunnelStatus.DISCONNECTED))
    }

    fun markError(context: Context, profileId: String, message: String) {
        update(context, profileId, TunnelStatusInfo(TunnelStatus.ERROR, message))
    }

    fun clearActiveStates(context: Context) {
        val currentState = load(context).profileStates.toMutableMap()
        val iterator = currentState.entries.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.status == TunnelStatus.CONNECTING || entry.value.status == TunnelStatus.CONNECTED) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_STATES, gson.toJson(currentState))
            }
            context.sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(context.packageName))
        }
    }

    fun resetAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_STATES)
        }
        context.sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(context.packageName))
    }

    private fun update(
        context: Context, profileId: String, info: TunnelStatusInfo
    ) {
        val currentState = load(context).profileStates.toMutableMap()
        if (info.status == TunnelStatus.DISCONNECTED) {
            currentState.remove(profileId)
        } else {
            currentState[profileId] = info
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_STATES, gson.toJson(currentState))
        }
        context.sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(context.packageName))
    }
}
