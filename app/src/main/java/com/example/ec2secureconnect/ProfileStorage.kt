package com.example.ec2secureconnect

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.content.edit

object ProfileStorage {

    private const val PREFS_NAME = "ssm_profiles"
    private const val KEY_PROFILES = "profiles"
    private val gson = Gson()
    private val listType = object : TypeToken<List<SsmProfile>>() {}.type

    fun loadProfiles(context: Context): List<SsmProfile> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null) ?: return emptyList()
        return gson.fromJson(json, listType) ?: emptyList()
    }

    fun saveProfiles(context: Context, profiles: List<SsmProfile>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_PROFILES, gson.toJson(profiles))
        }
    }
}
