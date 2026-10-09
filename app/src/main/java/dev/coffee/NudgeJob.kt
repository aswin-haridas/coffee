package dev.coffee

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Fetches check-ins from coffee-backend's agent (GET /v1/nudges) about every 30 minutes and shows each one
 * as a notification that opens its chat. Polling with JobScheduler rather than real push: no Firebase,
 * at the cost of check-ins arriving up to ~30 minutes late (longer while the phone dozes).
 */
class NudgeJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        thread {
            val nudges = runCatching { Backend.nudges() }.getOrDefault(emptyList())
            main.post { // Store is Compose state, so it is only touched on the main thread
                nudges.forEach { show(this, Store.nudge(applicationContext, it)) }
                jobFinished(p, false)
            }
        }
        return true
    }

    override fun onStopJob(p: JobParameters) = true

    companion object {
        private const val ID = 1
        private val main = Handler(Looper.getMainLooper())

        /** Only when missing: rescheduling restarts the period, so frequent app launches would starve it. */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(ID) != null) return
            js.schedule(JobInfo.Builder(ID, ComponentName(ctx, NudgeJob::class.java))
                .setPeriodic(30 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build())
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
