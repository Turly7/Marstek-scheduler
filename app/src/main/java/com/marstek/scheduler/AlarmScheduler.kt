package com.marstek.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Equivalent Android de "cron" : programme des alarmes exactes quotidiennes qui
 * declenchent ScheduleReceiver, lequel enverra la commande UDP correspondante.
 *
 * Chaque batterie a 2 alarmes :
 *  - ACTION_SET_MANUAL a l'heure "manualStart"
 *  - ACTION_SET_AUTO   a l'heure "autoStart"
 *
 * requestCode = hash unique par (ip, action) pour ne pas ecraser les autres alarmes.
 */
object AlarmScheduler {

    const val ACTION_SET_MANUAL = "com.marstek.scheduler.ACTION_SET_MANUAL"
    const val ACTION_SET_AUTO = "com.marstek.scheduler.ACTION_SET_AUTO"
    const val EXTRA_IP = "extra_ip"
    const val EXTRA_PORT = "extra_port"
    const val EXTRA_LABEL = "extra_label"

    private fun requestCode(ip: String, action: String): Int =
        (ip + action).hashCode()

    fun scheduleAllForDevice(context: Context, device: DeviceSchedule) {
        cancelAllForDevice(context, device.ip)
        if (!device.enabled) return

        scheduleDaily(
            context = context,
            hhmm = device.manualStart,
            action = ACTION_SET_MANUAL,
            device = device
        )
        scheduleDaily(
            context = context,
            hhmm = device.autoStart,
            action = ACTION_SET_AUTO,
            device = device
        )
    }

    fun cancelAllForDevice(context: Context, ip: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (action in listOf(ACTION_SET_MANUAL, ACTION_SET_AUTO)) {
            val intent = Intent(context, ScheduleReceiver::class.java).apply {
                this.action = action
            }
            val pi = PendingIntent.getBroadcast(
                context,
                requestCode(ip, action),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }

    /** Reprogramme toutes les alarmes de tous les devices enregistres (utilise apres reboot). */
    suspend fun rescheduleAll(context: Context) {
        val devices = DeviceRepository.loadAll(context)
        devices.forEach { scheduleAllForDevice(context, it) }
    }

    private fun scheduleDaily(
        context: Context,
        hhmm: String,
        action: String,
        device: DeviceSchedule
    ) {
        val (hour, minute) = parseHhMm(hhmm) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_IP, device.ip)
            putExtra(EXTRA_PORT, device.port)
            putExtra(EXTRA_LABEL, device.label)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode(device.ip, action),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = nextTriggerMillis(hour, minute)

        // setExactAndAllowWhileIdle : declenche meme en Doze, necessite que l'utilisateur
        // ait accorde l'exemption d'optimisation batterie pour rester fiable a long terme.
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    private fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis
    }

    private fun parseHhMm(hhmm: String): Pair<Int, Int>? {
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return h to m
    }
}
