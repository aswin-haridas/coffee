package dev.coffee

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.concurrent.thread

/** A finished trip and what [tripStats] made of it. Times are epoch ms; [start] is its id. */
data class TripRow(
    val start: Long, val end: Long, val dist: Double, val moving: Double, val avg: Double, val max: Double,
    val mode: Mode, val style: Style?, val score: Int, val ha: Int, val hb: Int, val ht: Int,
)

/**
 * Trips at full detail, on the phone. [Places] takes a fix every few minutes, which is enough for visits but not for speed
 * or driving style, so when Android's activity recognition says you've started driving, riding, running or walking,
 * [TripService] records a fix every couple of seconds until you're still again. [process] turns the fixes into trips
 * (Drive.kt). Everything stays in trips.db; a fix every half minute also goes to the server, so the timeline's path is finer.
 */
object Trips {
    /** Store setting: "on" once the user turned trips on. Needs [Places] on, for location all the time. */
    const val KEY = "trips"

    fun permitted(ctx: Context) = Places.permitted(ctx) &&
        (Build.VERSION.SDK_INT < 29 || ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)

    private fun enabled(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, "") == "on"

    private val moving = listOf(DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE, DetectedActivity.RUNNING, DetectedActivity.WALKING, DetectedActivity.STILL)

    // Mutable: Play services fills in the transition
    private fun transitions(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, TripReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Idempotent; like [Places.start], the request lives in Play services until [stop], a reboot or an app update. */
    @SuppressLint("MissingPermission") // permitted() checks it
    fun start(ctx: Context) {
        if (!enabled(ctx) || !permitted(ctx)) return
        val req = ActivityTransitionRequest(moving.map { ActivityTransition.Builder().setActivityType(it).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build() })
        runCatching { ActivityRecognition.getClient(ctx).requestActivityTransitionUpdates(req, transitions(ctx)) }
    }

    @SuppressLint("MissingPermission")
    fun stop(ctx: Context) {
        runCatching { ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(transitions(ctx)) }
        ctx.stopService(Intent(ctx, TripService::class.java))
    }

    private var db: Db? = null
    fun db(ctx: Context) = db ?: synchronized(this) { db ?: Db(ctx.applicationContext).also { db = it } }

    class Db(ctx: Context) : SQLiteOpenHelper(ctx, "trips.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) = listOf(
            "CREATE TABLE points(t INTEGER PRIMARY KEY, lat REAL, lon REAL, acc REAL, speed REAL, act INTEGER)",
            "CREATE TABLE trips(t0 INTEGER PRIMARY KEY, t1 INTEGER, dist REAL, moving REAL, avg REAL, max REAL, mode TEXT, style TEXT, score INTEGER, ha INTEGER, hb INTEGER, ht INTEGER)",
            "CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)",
        ).forEach(db::execSQL)

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

        private fun <T> q(sql: String, vararg args: Any, map: (Cursor) -> T): List<T> =
            readableDatabase.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }

        private fun stats(s: TripStats) = ContentValues().apply {
            put("dist", s.distM); put("moving", s.movingS); put("avg", s.avgKmh); put("max", s.maxKmh); put("mode", s.mode.name)
            put("style", s.style?.name); put("score", s.score); put("ha", s.harshAccel); put("hb", s.harshBrake); put("ht", s.harshTurn)
        }

        fun add(p: Pt) = writableDatabase.insertWithOnConflict("points", null, ContentValues().apply {
            put("t", p.t); put("lat", p.lat); put("lon", p.lon); put("acc", p.acc); put("speed", p.speed); put("act", p.act)
        }, SQLiteDatabase.CONFLICT_IGNORE)

        private fun pts(from: Long, to: Long) = q("SELECT t,lat,lon,acc,speed,act FROM points WHERE t>=? AND t<=? ORDER BY t", from, to) {
            Pt(it.getLong(0), it.getDouble(1), it.getDouble(2), it.getFloat(3), it.getFloat(4), it.getInt(5))
        }

        /** Fixes of a trip, for drawing it. */
        fun path(t: TripRow) = pts(t.start, t.end).filter { it.acc <= Tune.MAX_ACC_M }

        fun trips(from: Long, to: Long) = q("SELECT t0,t1,dist,moving,avg,max,mode,style,score,ha,hb,ht FROM trips WHERE t0>=? AND t0<? ORDER BY t0", from, to) {
            TripRow(it.getLong(0), it.getLong(1), it.getDouble(2), it.getDouble(3), it.getDouble(4), it.getDouble(5), Mode.valueOf(it.getString(6)),
                it.getString(7)?.let(Style::valueOf), it.getInt(8), it.getInt(9), it.getInt(10), it.getInt(11))
        }

        /**
         * Turns new fixes into finished trips. The newest stretch may still be going on, so it waits, unless it's a trip
         * that ended a stay's length ago: then you've been still since, and it's done.
         */
        @Synchronized fun process() {
            val cursor = q("SELECT v FROM meta WHERE k='cursor'") { it.getLong(0) }.firstOrNull() ?: 0L
            val segs = segment(pts(cursor, Long.MAX_VALUE))
            val last = segs.lastOrNull() ?: return
            val done = if (last is Trip && System.currentTimeMillis() - last.end >= Tune.STAY_MIN_MS) segs else segs.dropLast(1)
            if (done.isEmpty()) return
            val w = writableDatabase
            w.beginTransaction()
            try {
                for (s in done) if (s is Trip) w.insertWithOnConflict("trips", null, stats(tripStats(s.pts)).apply { put("t0", s.start); put("t1", s.end) }, SQLiteDatabase.CONFLICT_REPLACE)
                w.insertWithOnConflict("meta", null, ContentValues().apply { put("k", "cursor"); put("v", if (done === segs) last.end else last.start) }, SQLiteDatabase.CONFLICT_REPLACE)
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }

        // Corrections
        fun setMode(t: TripRow, mode: Mode) = writableDatabase.update("trips", stats(tripStats(path(t), mode)), "t0=?", arrayOf("${t.start}"))
        fun delete(t: TripRow) = writableDatabase.delete("trips", "t0=?", arrayOf("${t.start}"))
    }
}

/** Activity transitions from Play services (start or stop recording), and re-registering after a reboot or update. */
class TripReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) return Trips.start(app)
        val e = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull() ?: return
        val i = Intent(app, TripService::class.java).putExtra("act", e.activityType)
        // Android lets an activity-transition broadcast start a foreground service from the background
        if (e.activityType != DetectedActivity.STILL) runCatching { app.startForegroundService(i) }
        else if (TripService.running) app.startService(i)
    }
}

/** Records a fix every 2 s (5 s on foot) while you're on the move; stops after [still] of being still. */
@SuppressLint("MissingPermission") // only started once Trips.permitted()
class TripService : Service() {
    companion object {
        @Volatile var running = false
        private const val still = 2 * 60_000L // a red light or a queue isn't the end of a trip
    }

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var act = -1
    private var lastShared = 0L

    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val db = Trips.db(this@TripService)
            for (l in r.locations) {
                if (l.accuracy > 100) continue
                db.add(Pt(l.time, l.latitude, l.longitude, l.accuracy, if (l.hasSpeed()) l.speed else -1f, act))
                if (l.time - lastShared >= 30_000) { // a finer path for the server's timeline
                    lastShared = l.time
                    Places.queue(this@TripService, listOf(l))
                }
            }
        }
    }

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("trip", "Trip recording", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "trip")
            .setContentTitle("Recording your trip")
            .setSmallIcon(R.drawable.ph_map_pin)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(2, n)
    }

    private val finish = Runnable { stopSelf() }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val a = i?.getIntExtra("act", -1) ?: -1
        if (a == DetectedActivity.STILL) {
            handler.postDelayed(finish, still)
        } else {
            handler.removeCallbacks(finish)
            if (a != act || act == -1) {
                act = a
                val fast = a == DetectedActivity.IN_VEHICLE || a == DetectedActivity.ON_BICYCLE
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, if (fast) 2_000 else 5_000).setMinUpdateDistanceMeters(5f).build()
                fused.removeLocationUpdates(cb)
                fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
            }
        }
        return START_NOT_STICKY // a restart without a transition wouldn't know if you're still moving
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(finish)
        fused.removeLocationUpdates(cb)
        val app = applicationContext
        thread {
            Trips.db(app).process()
            Places.upload(app)
        }
        super.onDestroy()
    }
}
