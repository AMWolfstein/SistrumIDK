package com.amwolfstein.sistrum.device

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID

// Existing listening rows refer to the ID in this store. Preserve that ID on upgrade.
private val Context.listeningIdentityStore by preferencesDataStore("pairing")
private val MobileIdKey = stringPreferencesKey("mobile_id")

internal class DeviceIdentity(context: Context) {
    private val store = context.applicationContext.listeningIdentityStore

    suspend fun id(): String = store.edit { preferences ->
        if (preferences[MobileIdKey].isNullOrBlank()) {
            preferences[MobileIdKey] = UUID.randomUUID().toString()
        }
    }[MobileIdKey]!!
}
