package dev.coffee

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Check-ins from coffee-backend's agent arrive as Firebase data messages, which wake the app even when
 * it isn't running. Each one becomes a new chat plus a notification that opens it.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = register(token)

    // Runs on a Firebase worker thread; wait for the main thread so the process isn't dropped mid-save
    override fun onMessageReceived(msg: RemoteMessage) {
        val text = msg.data["text"] ?: return
        val done = CountDownLatch(1)
        main.post { show(this, Store.nudge(applicationContext, text)); done.countDown() }
        done.await()
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())

        /** On launch: send the server our token and show any check-ins it couldn't push. */
        fun start(ctx: Context) {
            ctx.getSystemService(JobScheduler::class.java).cancelAll() // the polling job from 1.8.x
            FirebaseMessaging.getInstance().token.addOnSuccessListener(::register)
            thread {
                val queued = runCatching { Backend.nudges() }.getOrDefault(emptyList())
                main.post { queued.forEach { show(ctx, Store.nudge(ctx.applicationContext, it)) } }
            }
        }

        private fun register(token: String) {
            thread { runCatching { Backend.registerPush(token) } } // retried on the next launch if offline
        }

        private fun show(ctx: Context, chat: Chat) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("nudges", "Check-ins", NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(ctx, MainActivity::class.java).putExtra("chat", chat.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val id = chat.id.toInt()
            nm.notify(id, Notification.Builder(ctx, "nudges")
                .setSmallIcon(R.drawable.ph_smiley)
                .setContentTitle("Coffee")
                .setContentText(chat.title)
                .setContentIntent(PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .build())
        }
    }
}
