package com.munawar.ai

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.appDataStore by preferencesDataStore(name = "munawar_prefs")

object K {
    val API = stringPreferencesKey("api_key")
    val MODEL = stringPreferencesKey("model")
    val VERIFIED = booleanPreferencesKey("key_verified")
    val PERSONA = stringPreferencesKey("persona")
    val LANG = stringPreferencesKey("lang")
    val WAKE = booleanPreferencesKey("wake_word")
    val RATE = floatPreferencesKey("rate")
    val PITCH = floatPreferencesKey("pitch")
    val CC = stringPreferencesKey("country_code")
}

data class AppSettings(
    val apiKey: String = "",
    val keyVerified: Boolean = false,
    val model: String = "gemini-2.5-flash",
    val persona: Persona = Persona.NORMAL,
    val lang: String = "ur-PK",
    val wakeWord: Boolean = false,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val countryCode: String = "92",
)

class PrefsRepo(context: Context) {
    private val ds = context.applicationContext.appDataStore

    val flow: Flow<AppSettings> = ds.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            AppSettings(
                apiKey = p[K.API] ?: "",
                keyVerified = p[K.VERIFIED] ?: false,
                model = p[K.MODEL] ?: "gemini-2.5-flash",
                persona = Persona.from(p[K.PERSONA]),
                lang = p[K.LANG] ?: "ur-PK",
                wakeWord = p[K.WAKE] ?: false,
                rate = p[K.RATE] ?: 1.0f,
                pitch = p[K.PITCH] ?: 1.0f,
                countryCode = p[K.CC] ?: "92",
            )
        }

    suspend fun current(): AppSettings = flow.first()

    suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        ds.edit { it[key] = value }
    }

    suspend fun saveKey(key: String, model: String) {
        ds.edit {
            it[K.API] = key
            it[K.MODEL] = model
            it[K.VERIFIED] = true
        }
    }
}
