package com.example.ec2secureconnect

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "ssm_profiles_v2")

object ProfileStorage {
    private val KEY_PROFILES_ENCRYPTED = stringPreferencesKey("profiles_encrypted")
    private val gson = Gson()
    private val listType = object : TypeToken<List<SsmProfile>>() {}.type
    private const val KEYSET_NAME = "ssm_keyset"
    private const val PREFERENCE_FILE = "ssm_tink_prefs"
    private const val MASTER_KEY_URI = "android-keystore://ssm_master_key"

    init {
        AeadConfig.register()
    }

    private fun getAead(context: Context): Aead {
        return AndroidKeysetManager.Builder().withSharedPref(context, KEYSET_NAME, PREFERENCE_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM")).withMasterKeyUri(MASTER_KEY_URI)
            .build().keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    suspend fun loadProfiles(context: Context): List<SsmProfile> {
        val aead = getAead(context)
        val encryptedBase64 =
            context.dataStore.data.map { it[KEY_PROFILES_ENCRYPTED] }.first() ?: return emptyList()

        return try {
            val encryptedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val decryptedBytes = aead.decrypt(encryptedBytes, null)
            val json = String(decryptedBytes, Charsets.UTF_8)
            gson.fromJson(json, listType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveProfiles(context: Context, profiles: List<SsmProfile>) {
        val aead = getAead(context)
        val json = gson.toJson(profiles)
        val encryptedBytes = aead.encrypt(json.toByteArray(Charsets.UTF_8), null)
        val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.DEFAULT)

        context.dataStore.edit { it[KEY_PROFILES_ENCRYPTED] = encryptedBase64 }
    }
}
