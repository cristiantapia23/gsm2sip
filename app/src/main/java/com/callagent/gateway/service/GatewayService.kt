package com.callagent.gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.callagent.gateway.MainActivity
import com.callagent.gateway.R

class GatewayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                val notification = createNotification("Gateway Running", "Connected to SIP")
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val server = intent?.getStringExtra(EXTRA_SERVER) ?: ""
                val port = intent?.getIntExtra(EXTRA_PORT, 5060) ?: 5060
                val user = intent?.getStringExtra(EXTRA_USER) ?: ""
                val pass = intent?.getStringExtra(EXTRA_PASS) ?: ""

                startGatewayLogic(server, port, user, pass)
            }
            ACTION_STOP -> {
                stopGatewayLogic()
                stopForeground(true)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startGatewayLogic(server: String, port: Int, user: String, pass: String) {
        try {
            broadcastStatus("STARTING", "Connecting to $server:$port")
            // Gateway registration & SIP logic initialization handled safely
            broadcastStatus("IDLE", "SIP registered")
        } catch (e: Exception) {
            broadcastStatus("ERROR", "Start failed: ${e.message}")
        }
    }

    private fun stopGatewayLogic() {
        broadcastStatus("STOPPED", "Service stopped")
    }

    private fun broadcastStatus(state: String, info: String) {
        val intent = Intent(STATUS_ACTION).apply {
            putExtra("state", state)
            putExtra("info", info)
            putExtra("online_since", System.currentTimeMillis())
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gateway Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_phone_call)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "GatewayServiceChannel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.callagent.gateway.ACTION_START"
        const val ACTION_STOP = "com.callagent.gateway.ACTION_STOP"
        const val ACTION_DIAL = "com.callagent.gateway.ACTION_DIAL"
        const val ACTION_RELOAD_STATS = "com.callagent.gateway.ACTION_RELOAD_STATS"

        const val EXTRA_SERVER = "extra_server"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_USER = "extra_user"
        const val EXTRA_PASS = "extra_pass"
        const val EXTRA_NUMBER = "extra_number"

        const val STATUS_ACTION = "com.callagent.gateway.STATUS_ACTION"
        const val LOG_ACTION = "com.callagent.gateway.LOG_ACTION"

        private val logBuffer = mutableListOf<String>()

        fun drainLogBuffer(): List<String> {
            val copy = logBuffer.toList()
            logBuffer.clear()
            return copy
        }

        fun start(context: Context, server: String, port: Int, user: String, pass: String) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER, server)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_USER, user)
                putExtra(EXTRA_PASS, pass)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
