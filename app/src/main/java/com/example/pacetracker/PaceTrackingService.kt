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
    private var lastLocation: Location? = null
    private var lastTime: Long = 0L
    private var currentPaceStr: String = "--:--"
    private var lastSpeakTime: Long = 0L  // track last time TTS spoke


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
            val currentTime = System.currentTimeMillis()

            val speedMps = if (lastLocation != null && lastTime > 0L) {
                val distance = lastLocation!!.distanceTo(location).toDouble()
                val deltaTime = (currentTime - lastTime) / 1000.0
                if (deltaTime > 0.0) distance / deltaTime else location.speed.toDouble()
            } else {
                location.speed.toDouble()
            }

            lastLocation = location
            lastTime = currentTime

            val speedKmh = speedMps * 3.6
            val paceMinPerKm = if (speedKmh > 0) 60.0 / speedKmh else Double.POSITIVE_INFINITY

            currentPaceStr = if (paceMinPerKm.isInfinite() || paceMinPerKm > 20) {
                "--:--"
            } else {
                val minutes = paceMinPerKm.toInt()
                val seconds = ((paceMinPerKm - minutes) * 60).toInt()
                String.format("%d:%02d min/km", minutes, seconds)
            }

            // ---- SPEAK ONLY EVERY 1 MINUTE ----
            if (currentTime - lastSpeakTime > 60_000) {
                tts.speak(
                    "Your current pace is $currentPaceStr",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "paceId"
                )
                lastSpeakTime = currentTime
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
