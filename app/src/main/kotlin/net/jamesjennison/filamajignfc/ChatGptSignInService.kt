package net.jamesjennison.filamajignfc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app's process running while ChatGPT sign-in is open in the browser.
 *
 * The sign-in result arrives on a loopback socket inside this process. Without a foreground
 * service, the system freezes the app seconds after the browser comes to the front, and the
 * browser's redirect is never answered. The service does no work of its own; it runs only for
 * the duration of a sign-in attempt and uses the time-limited "short service" type.
 */
class ChatGptSignInService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ChatGPT sign-in", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Waiting for ChatGPT sign-in")
            .setContentText("Finish signing in in your browser, then return to SpoolForge.")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    /** A short service must stop itself when its time allowance ends. */
    override fun onTimeout(startId: Int) { stopSelf() }

    companion object {
        private const val CHANNEL_ID = "chatgpt-sign-in"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context) {
            // Starting can be refused if the app is not in the foreground. Sign-in still works
            // then, but the user may need to return to the app to finish it.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ChatGptSignInService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ChatGptSignInService::class.java)) }
        }
    }
}
