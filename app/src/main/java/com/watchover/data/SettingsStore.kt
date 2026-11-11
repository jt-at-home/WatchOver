package com.watchover.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Persists [Settings] as one atomic string value (design §13). */
class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("settings_v1")

    val settings: Flow<Settings> = context.settingsDataStore.data.map { prefs ->
        SettingsCodec.decode(FlatMap.decode(prefs[key].orEmpty()))
    }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings): Settings {
        var updated = Settings()
        context.settingsDataStore.edit { prefs ->
            val current = SettingsCodec.decode(FlatMap.decode(prefs[key].orEmpty()))
            updated = transform(current).sanitized()
            prefs[key] = FlatMap.encode(SettingsCodec.encode(updated))
        }
        return updated
    }

    suspend fun set(newSettings: Settings) {
        val safe = newSettings.sanitized()
        context.settingsDataStore.edit { prefs ->
            prefs[key] = FlatMap.encode(SettingsCodec.encode(safe))
        }
    }
}
