package com.example.pacetracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.util.*

class PaceTrackingService : Service(), TextToSpeech.OnInitListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var tts: TextToSpeech
    private var currentPaceStr: String = "--:--"
    private var lastSpeakTime: Long = 0L  // track last time TTS spoke
    private val locationBuffer = ArrayDeque<Pair<Location, Long>>()
    private val bufferDurationMs = 5000L   // 5-second smoothing window
    private var smoothedSpeed = -1.0       // for exponential smoothing


    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate() {
        super.onCreate()

        tts = TextToSpeech(this, this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        startForegroundServiceNotification()
        startLocationUpdates()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Notification for foreground service ----
    private fun startForegroundServiceNotification() {
        val channelId = "pace_tracker_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Pace Tracker", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Pace Tracker")
            .setContentText("Tracking pace...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(1000)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private val locationCallback = object : LocationCallback() {

        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val now = System.currentTimeMillis()

            // 1. Ignore bad GPS accuracy
            if (location.accuracy > 20f) return   // <–– important

            // 2. Add new point to buffer
            locationBuffer.addLast(location to now)

            // Remove old points
            while (locationBuffer.isNotEmpty() &&
                locationBuffer.first().second < now - bufferDurationMs) {
                locationBuffer.removeFirst()
            }

            // 3. Calculate speed
            val rawSpeedMps = when {
                location.hasSpeed() -> location.speed.toDouble()   // best source
                locationBuffer.size > 1 -> {
                    // fallback: distance over last ~5 seconds
                    var dist = 0.0
                    val pts = locationBuffer.toList()
                    for (i in 1 until pts.size) {
                        dist += pts[i-1].first.distanceTo(pts[i].first)
                    }
                    val dt = (pts.last().second - pts.first().second) / 1000.0
                    if (dt > 0) dist / dt else 0.0
                }
                else -> 0.0
            }

            // 4. Apply exponential moving average for smoothness
            val alpha = 0.25  // higher = more reactive, lower = smoother
            smoothedSpeed = when {
                smoothedSpeed < 0 -> rawSpeedMps     // first value
                else -> (alpha * rawSpeedMps) + ((1 - alpha) * smoothedSpeed)
            }

            val speedKmh = smoothedSpeed * 3.6
            val paceMinPerKm = if (speedKmh > 0.5) 60.0 / speedKmh else Double.POSITIVE_INFINITY

            // 5. Format pace into readable string
            currentPaceStr = if (paceMinPerKm.isInfinite() || paceMinPerKm > 20) {
                "--:--"
            } else {
                val min = paceMinPerKm.toInt()
                val sec = ((paceMinPerKm - min) * 60).toInt()
                String.format("%d:%02d min/km", min, sec)
            }

            // 6. Speak every 1 minute
            if (now - lastSpeakTime > 60_000) {
                tts.speak(
                    "Your current pace is $currentPaceStr",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "paceId"
                )
                lastSpeakTime = now
            }
        }
    }


    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Stop service if app is swiped away
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.UK
    }
}
