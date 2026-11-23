package com.maintenance.assetlog.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.maintenance.assetlog.MainActivity
import com.maintenance.assetlog.data.LogRepository
import com.maintenance.assetlog.data.asDate
import com.maintenance.assetlog.data.due
import com.maintenance.assetlog.domain.Priority
import com.maintenance.assetlog.domain.outstanding
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderDependencies {
    fun repository(): dagger.Lazy<LogRepository>
}

class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            notifyDue(applicationContext)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.failure()
        }
    }

    private suspend fun notifyDue(context: Context) {
        val preferences = Preferences(context)
        val settings = preferences.settings.first()
        val manager = NotificationManagerCompat.from(context)
        if (!settings.notifications || !manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val channel = "maintenance-due"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "ميزان · الالتزامات والتذكيرات", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val repository = EntryPointAccessors.fromApplication(context, ReminderDependencies::class.java).repository().get()
        val assets = repository.queries.assets().first().associateBy { it.id }
        val today = LocalDate.now()
        val notices = buildMap {
            repository.ledger.operations().first().filter { it.remind && it.outstanding() }.forEach { item ->
                val dueDate = item.dueOn.ifBlank { item.endsOn }.asDate()
                val kmDue = item.nextKm?.let { target -> assets[item.vehicleId]?.odometer?.let { it >= target } } == true
                val phase = when {
                    kmDue -> "العداد"
                    dueDate == null -> null
                    dueDate < today -> "متأخر"
                    dueDate == today -> "اليوم"
                    dueDate <= today.plusDays((item.reminderDays ?: settings.days).toLong()) -> "قريب"
                    else -> null
                }
                if (phase != null) {
                    put("operation/${item.id}/$dueDate/${item.nextKm}/$phase", "ميزان · التزام $phase" to "operation/${item.id}")
                }
            }
            repository.queries.cycles().first().forEach { entry ->
                val asset = assets[entry.assetId] ?: return@forEach
                val due = entry.due(today, asset.odometer, settings.days)
                if (due.priority in setOf(Priority.LATE, Priority.TODAY, Priority.SOON)) {
                    put("maintenance/${entry.id}/${due.priority}", "الصيانة ${due.priority.label}" to "maintenance/${entry.id}")
                }
            }
            repository.queries.remindingParts().forEach { part ->
                listOf("الضمان" to part.warranty, "الصلاحية" to part.expires).forEach { (label, value) ->
                    val date = value.asDate()
                    if (date != null && !date.isAfter(today.plusDays(settings.days.toLong()))) {
                        val phase = if (date.isBefore(today)) "انتهت مدة" else "اقترب انتهاء"
                        put("part/${part.id}/$label/$date/$phase", "$phase $label" to "asset/${part.assetId}")
                    }
                }
            }
        }
        preferences.markNotifications(notices.keys, today).forEach { key ->
            val (title, route) = notices.getValue(key)
            val open = Intent(context, MainActivity::class.java).setAction("com.maintenance.assetlog.OPEN")
                .setData(android.net.Uri.parse("assetlog://open/$route"))
            val snooze = Intent(context, SnoozeReceiver::class.java)
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title)
                .setContentText("افتح التطبيق لمراجعة التفاصيل")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE))
                .addAction(0, "أجّل إلى الغد", PendingIntent.getBroadcast(context, 0, snooze, PendingIntent.FLAG_IMMUTABLE))
                .build()
            manager.notify(key, 1, notification)
        }
    }
}

fun scheduleReminders(context: Context, enabled: Boolean) {
    val manager = WorkManager.getInstance(context)
    if (enabled) {
        manager.enqueueUniquePeriodicWork("daily-reminders", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build())
        manager.enqueueUniqueWork("check-reminders", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ReminderWorker>().build())
    } else {
        manager.cancelUniqueWork("daily-reminders")
        manager.cancelUniqueWork("check-reminders")
        NotificationManagerCompat.from(context).cancelAll()
    }
}

class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Preferences(context).snooze(LocalDate.now())
                NotificationManagerCompat.from(context).cancelAll()
            } finally {
                result.finish()
            }
        }
    }
}
