package com.maintenance.assetlog.core

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.store by preferencesDataStore("maintenance-settings")

data class Settings(
    val theme: String = "النظام", val notifications: Boolean = false,
    val days: Int = 7, val currency: String = "SAR", val locked: Boolean = false
)

class Preferences(private val context: Context) {
    val settings = context.store.data.map {
        Settings(it[THEME] ?: "النظام", it[NOTIFICATIONS] ?: false,
            it[DAYS] ?: 7, it[CURRENCY] ?: "SAR", it[LOCKED] ?: false)
    }

    suspend fun save(value: Settings) {
        require(value.days in 0..365 && value.currency.matches(Regex("[A-Z]{3}")))
        context.store.edit {
            it[THEME] = value.theme
            it[NOTIFICATIONS] = value.notifications
            it[DAYS] = value.days
            it[CURRENCY] = value.currency
            it[LOCKED] = value.locked
        }
    }

    suspend fun markNotifications(due: Set<String>, today: LocalDate): Set<String> {
        val pending = mutableSetOf<String>()
        context.store.edit {
            if (it[SNOOZE] != today.toString() && it[NOTIFICATIONS] == true) {
                val old = it[SENT].orEmpty().intersect(due)
                pending.addAll(due - old)
                it[SENT] = old + pending
            }
        }
        return pending
    }

    suspend fun snooze(today: LocalDate) {
        context.store.edit { it[SNOOZE] = today.toString(); it.remove(SENT) }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val DAYS = intPreferencesKey("days")
        val CURRENCY = stringPreferencesKey("currency")
        val LOCKED = booleanPreferencesKey("locked")
        val SENT = stringSetPreferencesKey("sent")
        val SNOOZE = stringPreferencesKey("snooze")
    }
}
