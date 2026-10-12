package dev.coffee

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Check-ins from coffee-backend's agent arrive as Firebase data messages, which wake the app even when
 * it isn't running. Each one joins the chat, and the notification gathers them until the app is opened.
 * Coffee's other messages (place check-ins, replies that land after you left) share that notification.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = register(token)

    // Runs on a Firebase worker thread; wait for the main thread so the process isn't dropped mid-save
    override fun onMessageReceived(msg: RemoteMessage) {
        val text = msg.data["text"] ?: return
        val done = CountDownLatch(1)
        main.post { checkIn(this, text); done.countDown() }
        done.await()
    }

    companion object {
        const val ID = 1
        private val main = Handler(Looper.getMainLooper())

        /** On launch: send the server our token and show any check-ins it couldn't push. */
        fun start(ctx: Context) {
            ctx.getSystemService(JobScheduler::class.java).cancelAll() // the polling job from 1.8.x
            FirebaseMessaging.getInstance().token.addOnSuccessListener(::register)
            thread {
                val queued = runCatching { Backend.nudges() }.getOrDefault(emptyList())
                main.post { queued.forEach { checkIn(ctx, it) } }
            }
        }

        private fun register(token: String) {
            thread { runCatching { Backend.registerPush(token) } } // retried on the next launch if offline
        }

        /** Coffee texting first: joins the chat and notifies. Main thread. */
        fun checkIn(ctx: Context, text: String) {
            Store.nudge(ctx.applicationContext, text)
            show(ctx, text)
        }

        /** Shows one of Coffee's messages, unless the chat is on screen. */
        fun show(ctx: Context, text: String) {
            if (MainActivity.visible) return // it's already on screen in the chat
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("nudges", "Messages", NotificationManager.IMPORTANCE_DEFAULT))
            // One conversation notification: messages not yet seen stack up in it, each in full
            val style = nm.activeNotifications.find { it.id == ID }?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification) }
                ?: NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            style.addMessage(text, System.currentTimeMillis(), Person.Builder().setName("Coffee").build())
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            nm.notify(ID, NotificationCompat.Builder(ctx, "nudges")
                .setSmallIcon(R.drawable.ph_smiley)
                .setStyle(style)
                .setContentIntent(PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .build())
        }
    }
}
