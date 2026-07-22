package com.marstek.scheduler

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "marstek_devices")

/**
 * Configuration d'une batterie geree par l'app :
 * - identite/IP du device
 * - port UDP (defini dans l'app Marstek, cf. doc section 2.2.1)
 * - plage "Manuel" (debut/fin + puissance)
 * - plage "Auto" (debut/fin -> l'heure de fin declenche le retour en Auto)
 * - activation des taches planifiees
 */
@Serializable
data class DeviceSchedule(
    val ip: String,
    val port: Int = 30000,
    val label: String,
    val bleMac: String = "",
    val manualStart: String = "22:00",
    val manualEnd: String = "06:00",
    val manualPower: Int = 500,
    val autoStart: String = "06:30",
    val enabled: Boolean = true
)

object DeviceRepository {
    private val KEY_DEVICES = stringPreferencesKey("devices_json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    suspend fun loadAll(context: Context): List<DeviceSchedule> {
        val prefs = context.dataStore.data.first()
        val raw = prefs[KEY_DEVICES] ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveAll(context: Context, devices: List<DeviceSchedule>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICES] = json.encodeToString(devices)
        }
    }

    suspend fun upsert(context: Context, device: DeviceSchedule) {
        val current = loadAll(context).toMutableList()
        val idx = current.indexOfFirst { it.ip == device.ip }
        if (idx >= 0) current[idx] = device else current.add(device)
        saveAll(context, current)
    }

    suspend fun remove(context: Context, ip: String) {
        val current = loadAll(context).filterNot { it.ip == ip }
        saveAll(context, current)
    }
}
