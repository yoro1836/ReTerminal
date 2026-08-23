package com.rk.terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.rk.settings.Settings
import com.rk.terminal.backend.TerminalSessionBackend
import com.rk.terminal.backend.avf.AvfTerminalBackend
import com.rk.resources.drawables
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

class SessionService : Service() {
    private val sessions = hashMapOf<String, TerminalSession>()
    private val backends = hashMapOf<String, TerminalSessionBackend>()
    val sessionList = mutableStateMapOf<String, Int>()
    val sessionOrder = mutableStateListOf<String>()
    var currentSession = mutableStateOf(Pair("main", WorkingMode.AVF))

    inner class SessionBinder : Binder() {
        fun getService(): SessionService = this@SessionService
        fun terminateAllSessions() {

            backends.values.forEach { it.close() }
            backends.clear()
            sessions.clear()
            sessionList.clear()
            sessionOrder.clear()
            updateNotification()
        }

        fun createSession(
            id: String,
            client: TerminalSessionClient,
        ): TerminalSession {
            check(sessions.isEmpty()) { "Only one AVF session can run at a time" }
            val backend = AvfTerminalBackend(this@SessionService, id)
            return backend.createSession(client).also {
                backends[id] = backend
                sessions[id] = it
                sessionList[id] = WorkingMode.AVF
                if (!sessionOrder.contains(id)) {
                    sessionOrder.add(id)
                }
                updateNotification()
            }
        }

        fun getSession(id: String): TerminalSession? = sessions[id]

        fun renameSession(oldId: String, newId: String): Boolean {
            val trimmed = newId.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed == oldId) return true
            if (sessions.containsKey(trimmed)) return false

            val mode = sessionList.remove(oldId) ?: com.rk.settings.Settings.working_Mode
            val session = sessions.remove(oldId) ?: return false
            val backend = backends.remove(oldId)
            sessions[trimmed] = session
            backend?.let { backends[trimmed] = it }
            sessionList[trimmed] = mode

            val idx = sessionOrder.indexOf(oldId)
            if (idx != -1) {
                sessionOrder[idx] = trimmed
            } else {
                sessionOrder.add(trimmed)
            }

            if (currentSession.value.first == oldId) {
                currentSession.value = Pair(trimmed, mode)
            }
            return true
        }

        fun moveSession(fromIndex: Int, toIndex: Int) {
            if (fromIndex in sessionOrder.indices && toIndex in sessionOrder.indices && fromIndex != toIndex) {
                val item = sessionOrder.removeAt(fromIndex)
                sessionOrder.add(toIndex, item)
            }
        }

        fun sortSessions(ascending: Boolean = true) {
            val sorted = if (ascending) sessionOrder.sorted() else sessionOrder.sortedDescending()
            sessionOrder.clear()
            sessionOrder.addAll(sorted)
        }

        fun terminateSession(id: String) {
            backends.remove(id)?.close()
            sessions.remove(id)
            sessionList.remove(id)
            sessionOrder.remove(id)
            if (sessions.isEmpty()) {
                stopSelf()
            } else {
                updateNotification()
            }
        }
    }

    private val binder = SessionBinder()
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }
    private val powerManager by lazy {
        getSystemService(PowerManager::class.java)
    }
    private var wakeLock: PowerManager.WakeLock? = null

    private val isWakeLockHeld: Boolean
        get() = wakeLock?.isHeld == true

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        releaseWakeLock()
        backends.values.forEach { it.close() }
        backends.clear()
        sessions.clear()
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        if (Settings.avfWakelockEnabled) {
            setWakeLockEnabled(true, notify = false)
        }
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_WAKELOCK -> setWakeLockEnabled(!isWakeLockHeld)
            ACTION_EXIT -> {
                backends.values.forEach { it.close() }
                backends.clear()
                sessions.clear()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val wakeLockIntent = Intent(this, SessionService::class.java).apply {
            action = ACTION_TOGGLE_WAKELOCK
        }
        val wakeLockPendingIntent = PendingIntent.getService(
            this,
            2,
            wakeLockIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exitIntent = Intent(this, SessionService::class.java).apply {
            action = ACTION_EXIT
        }
        val exitPendingIntent = PendingIntent.getService(
            this,
            1,
            exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ReTerminal")
            .setContentText(getNotificationContentText())
            .setSmallIcon(drawables.terminal)
            .setContentIntent(pendingIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    null,
                    if (isWakeLockHeld) "Release wakelock" else "Acquire wakelock",
                    wakeLockPendingIntent,
                ).build(),
            )
            .addAction(
                NotificationCompat.Action.Builder(null, "EXIT", exitPendingIntent).build(),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private val CHANNEL_ID = "session_service_channel"

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Session Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Notification for Terminal Service"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun setWakeLockEnabled(enabled: Boolean, notify: Boolean = true) {
        Settings.avfWakelockEnabled = enabled
        if (enabled) {
            val lock = wakeLock ?: powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:avf",
            ).apply {
                setReferenceCounted(false)
            }.also { wakeLock = it }
            if (!lock.isHeld) {
                lock.acquire()
            }
        } else {
            releaseWakeLock()
        }
        if (notify) {
            updateNotification()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
    }

    private fun getNotificationContentText(): String {
        val count = sessions.size
        val sessionText = if (count == 1) "1 session running" else "$count sessions running"
        return if (isWakeLockHeld) "$sessionText · wakelock held" else sessionText
    }

    private companion object {
        const val ACTION_EXIT = "com.rk.terminal.action.EXIT"
        const val ACTION_TOGGLE_WAKELOCK = "com.rk.terminal.action.TOGGLE_WAKELOCK"
        const val NOTIFICATION_ID = 1
    }
}
