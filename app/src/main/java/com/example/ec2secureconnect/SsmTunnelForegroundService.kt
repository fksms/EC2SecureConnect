package com.example.ec2secureconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson

class SsmTunnelForegroundService : Service() {

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeProfile: SsmProfile? = null
    private var tunnelProcess: GoTunnelSessionProcess? = null

    @Volatile
    private var isStopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileJson =
                    intent.getStringExtra(EXTRA_PROFILE_JSON) ?: return START_NOT_STICKY
                val profile =
                    gson.fromJson(profileJson, SsmProfile::class.java) ?: return START_NOT_STICKY
                startTunnel(profile)
            }

            ACTION_DISCONNECT -> stopTunnel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tunnelProcess?.stop()
        tunnelProcess = null
        activeProfile = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTunnel(profile: SsmProfile) {
        tunnelProcess?.stop()
        notificationManager().cancel(ERROR_NOTIFICATION_ID)
        isStopping = false
        activeProfile = profile
        TunnelConnectionStore.markConnecting(this, profile.id)
        startForeground(NOTIFICATION_ID, buildNotification(profile, TunnelStatus.CONNECTING))

        val process = GoTunnelSessionProcess(
            context = applicationContext,
            profile = profile,
            callback = object : GoTunnelSessionProcess.Callback {
                override fun onConnected(message: String) {
                    mainHandler.post {
                        if (isStopping || activeProfile?.id != profile.id) {
                            return@post
                        }
                        TunnelConnectionStore.markConnected(
                            this@SsmTunnelForegroundService, profile.id
                        )
                        notificationManager().notify(
                            NOTIFICATION_ID, buildNotification(profile, TunnelStatus.CONNECTED)
                        )
                    }
                }

                override fun onStopped(message: String) {
                    mainHandler.post {
                        if (isStopping || activeProfile?.id != profile.id) {
                            return@post
                        }
                        activeProfile = null
                        tunnelProcess = null
                        TunnelConnectionStore.markDisconnected(
                            this@SsmTunnelForegroundService, profile.id
                        )
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }

                override fun onError(message: String) {
                    mainHandler.post {
                        if (isStopping || activeProfile?.id != profile.id) {
                            return@post
                        }
                        handleFailure(profile, message)
                    }
                }
            })
        tunnelProcess = process
        try {
            process.start()
        } catch (error: Exception) {
            handleFailure(profile, error.message ?: "Failed to start ssm-client")
        }
    }

    private fun stopTunnel() {
        isStopping = true
        val profileId = activeProfile?.id
        tunnelProcess?.stop()
        tunnelProcess = null
        activeProfile = null
        TunnelConnectionStore.markDisconnected(this, profileId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleFailure(profile: SsmProfile, message: String) {
        tunnelProcess?.stop()
        tunnelProcess = null
        activeProfile = null
        TunnelConnectionStore.markError(this, profile.id, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager().notify(
            ERROR_NOTIFICATION_ID, buildFailureNotification(message)
        )
        stopSelf()
    }

    private fun buildNotification(profile: SsmProfile, status: TunnelStatus): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1, Intent(this, SsmTunnelForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val titleRes = if (status == TunnelStatus.CONNECTED) {
            R.string.notification_connected_title
        } else {
            R.string.notification_connecting_title
        }
        val contentText = if (status == TunnelStatus.CONNECTED) {
            getString(
                R.string.notification_connected_message,
                profile.name,
                profile.localPort,
                profile.remotePort
            )
        } else {
            getString(R.string.notification_connecting_message, profile.name)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(titleRes)).setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(openAppIntent)
            .setOngoing(status != TunnelStatus.DISCONNECTED && status != TunnelStatus.ERROR)
            .setOnlyAlertOnce(true).addAction(
                0, getString(R.string.notification_stop_action), disconnectIntent
            ).build()
    }

    private fun buildFailureNotification(message: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.notification_failed_title)).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openAppIntent).setAutoCancel(true).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "ssm_tunnel_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ERROR_NOTIFICATION_ID = 1002
        private const val EXTRA_PROFILE_JSON = "extra_profile_json"
        private const val ACTION_CONNECT = "com.example.android_ssm.action.CONNECT"
        private const val ACTION_DISCONNECT = "com.example.android_ssm.action.DISCONNECT"

        fun start(context: Context, profile: SsmProfile) {
            val intent = Intent(context, SsmTunnelForegroundService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_PROFILE_JSON, Gson().toJson(profile))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SsmTunnelForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}
