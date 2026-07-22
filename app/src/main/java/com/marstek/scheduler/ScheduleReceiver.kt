package com.marstek.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Recoit le declenchement de l'AlarmManager, envoie la commande UDP correspondante,
 * puis reprogramme la meme alarme pour le lendemain (les alarmes exactes Android
 * ne sont pas nativement repetitives de facon fiable, donc on la re-arme a chaque tir).
 *
 * En cas d'echec (timeout, device injoignable...), une notification est envoyee
 * pour que l'utilisateur puisse verifier manuellement -- il n'y a pas de retry
 * automatique cache : mieux vaut prevenir que produire un etat incoherent silencieux.
 */
class ScheduleReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "marstek_scheduler_alerts"
        private const val CHANNEL_NAME = "Marstek Scheduler"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ip = intent.getStringExtra(AlarmScheduler.EXTRA_IP) ?: return
        val port = intent.getIntExtra(AlarmScheduler.EXTRA_PORT, 30000)
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: ip
        val action = intent.action ?: return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val devices = DeviceRepository.loadAll(context)
                val device = devices.firstOrNull { it.ip == ip }

                val result = when (action) {
                    AlarmScheduler.ACTION_SET_MANUAL -> {
                        val d = device ?: return@launch
                        MarstekUdpClient.setManualMode(
                            ip = ip,
                            port = port,
                            startTime = d.manualStart,
                            endTime = d.manualEnd,
                            power = d.manualPower
                        )
                    }
                    AlarmScheduler.ACTION_SET_AUTO -> {
                        MarstekUdpClient.setAutoMode(ip = ip, port = port)
                    }
                    else -> null
                }

                if (result != null && result.isFailure) {
                    notifyFailure(context, label, action, result.exceptionOrNull()?.message)
                }

                // Re-arme l'alarme identique pour le jour suivant
                device?.let { AlarmScheduler.scheduleAllForDevice(context, it) }

            } catch (e: Exception) {
                notifyFailure(context, label, action, e.message)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun notifyFailure(context: Context, label: String, action: String, errorMsg: String?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }
        val what = if (action == AlarmScheduler.ACTION_SET_MANUAL) "passage en Manuel" else "retour en Auto"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Echec : $what ($label)")
            .setContentText(errorMsg ?: "Erreur inconnue - device injoignable ?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(label.hashCode(), notification)
    }
}
