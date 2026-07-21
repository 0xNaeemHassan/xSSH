package com.xssh.feature.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val REQUIRE_BIOMETRIC_KEY = booleanPreferencesKey("require_biometric_before_connect")

private object SessionPrefsStore {
    @Volatile private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> {
        return instance ?: synchronized(this) {
            instance ?: PreferenceDataStoreFactory.create(
                produceFile = { context.applicationContext.preferencesDataStoreFile("session_prefs.preferences_pb") },
            ).also { instance = it }
        }
    }
}

fun requireBiometricFlow(context: Context): Flow<Boolean> =
    SessionPrefsStore.get(context).data.map { prefs -> prefs[REQUIRE_BIOMETRIC_KEY] ?: false }

suspend fun setRequireBiometric(
    context: Context,
    enabled: Boolean,
) {
    SessionPrefsStore.get(context).edit { prefs ->
        prefs[REQUIRE_BIOMETRIC_KEY] = enabled
    }
}
