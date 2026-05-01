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
import java.util.concurrent.ConcurrentHashMap

class SsmTunnelForegroundService : Service() {

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeSessions = ConcurrentHashMap<String, GoTunnelSessionProcess>()
    private val activeProfiles = ConcurrentHashMap<String, SsmProfile>()
    private val sessionStatuses = ConcurrentHashMap<String, TunnelStatus>()

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

            ACTION_DISCONNECT -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (profileId != null) {
                    stopTunnel(profileId)
                } else {
                    stopAllTunnels()
                }
            }

            ACTION_REFRESH -> {
                refreshState()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        activeSessions.values.forEach { it.stop() }
        activeSessions.clear()
        activeProfiles.clear()
        TunnelConnectionStore.clearActiveStates(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTunnel(profile: SsmProfile) {
        if (activeSessions.containsKey(profile.id)) {
            return
        }

        notificationManager().cancel(profile.id.hashCode())
        activeProfiles[profile.id] = profile
        sessionStatuses[profile.id] = TunnelStatus.CONNECTING
        TunnelConnectionStore.markConnecting(this, profile.id)
        updateNotification()

        val process = GoTunnelSessionProcess(
            context = applicationContext,
            profile = profile,
            callback = object : GoTunnelSessionProcess.Callback {
                override fun onConnected(message: String) {
                    mainHandler.post {
                        if (!activeSessions.containsKey(profile.id)) return@post
                        sessionStatuses[profile.id] = TunnelStatus.CONNECTED
                        TunnelConnectionStore.markConnected(
                            this@SsmTunnelForegroundService, profile.id
                        )
                        updateNotification()
                    }
                }

                override fun onStopped(message: String) {
                    mainHandler.post {
                        if (!activeSessions.containsKey(profile.id)) return@post
                        cleanupSession(profile.id)
                        TunnelConnectionStore.markDisconnected(
                            this@SsmTunnelForegroundService, profile.id
                        )
                        checkServiceStop()
                    }
                }

                override fun onError(message: String) {
                    mainHandler.post {
                        if (!activeSessions.containsKey(profile.id)) return@post
                        handleFailure(profile, message)
                    }
                }
            })
        activeSessions[profile.id] = process
        try {
            process.start()
        } catch (error: Exception) {
            handleFailure(profile, error.message ?: "Failed to start ssm-client")
        }
    }

    private fun stopTunnel(profileId: String) {
        activeSessions[profileId]?.stop()
        activeSessions.remove(profileId)
        activeProfiles.remove(profileId)
        sessionStatuses.remove(profileId)
        TunnelConnectionStore.markDisconnected(this, profileId)
        checkServiceStop()
    }

    private fun stopAllTunnels() {
        activeSessions.values.forEach { it.stop() }
        activeSessions.clear()
        activeProfiles.clear()
        sessionStatuses.clear()
        TunnelConnectionStore.clearActiveStates(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupSession(profileId: String) {
        activeSessions.remove(profileId)
        activeProfiles.remove(profileId)
        sessionStatuses.remove(profileId)
    }

    private fun checkServiceStop() {
        if (activeSessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private fun handleFailure(profile: SsmProfile, message: String) {
        cleanupSession(profile.id)
        TunnelConnectionStore.markError(this, profile.id, message)
        notificationManager().notify(
            profile.id.hashCode(), buildFailureNotification(message)
        )
        checkServiceStop()
    }

    private fun refreshState() {
        sessionStatuses.forEach { (id, status) ->
            when (status) {
                TunnelStatus.CONNECTING -> TunnelConnectionStore.markConnecting(this, id)
                TunnelStatus.CONNECTED -> TunnelConnectionStore.markConnected(this, id)
                else -> {}
            }
        }
        checkServiceStop()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectAllIntent = PendingIntent.getService(
            this, 1, Intent(this, SsmTunnelForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeCount = activeProfiles.size
        val title = if (activeCount == 1) {
            val profile = activeProfiles.values.first()
            getString(R.string.notification_connected_title) + ": " + profile.name
        } else {
            getString(R.string.notification_channel_name) + " ($activeCount active)"
        }

        val contentText = if (activeCount == 1) {
            val profile = activeProfiles.values.first()
            getString(
                R.string.notification_connected_message,
                profile.name,
                profile.localPort,
                profile.remotePort
            )
        } else {
            activeProfiles.values.joinToString("\n") { p ->
                "${p.name}: localhost:${p.localPort} -> ${p.remotePort}"
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle(title)
            .setContentText(if (activeCount == 1) contentText else getString(R.string.notification_connected_title))
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(openAppIntent).setOngoing(true).setOnlyAlertOnce(true).addAction(
                0, getString(R.string.notification_stop_action), disconnectAllIntent
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
        private const val EXTRA_PROFILE_JSON = "extra_profile_json"
        private const val EXTRA_PROFILE_ID = "extra_profile_id"
        private const val ACTION_CONNECT = "com.example.android_ssm.action.CONNECT"
        private const val ACTION_DISCONNECT = "com.example.android_ssm.action.DISCONNECT"
        private const val ACTION_REFRESH = "com.example.android_ssm.action.REFRESH"

        fun start(context: Context, profile: SsmProfile) {
            val intent = Intent(context, SsmTunnelForegroundService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_PROFILE_JSON, Gson().toJson(profile))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, profileId: String? = null) {
            val intent = Intent(context, SsmTunnelForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
                if (profileId != null) {
                    putExtra(EXTRA_PROFILE_ID, profileId)
                }
            }
            context.startService(intent)
        }

        fun refresh(context: Context) {
            val intent = Intent(context, SsmTunnelForegroundService::class.java).apply {
                action = ACTION_REFRESH
            }
            context.startService(intent)
        }
    }
}
