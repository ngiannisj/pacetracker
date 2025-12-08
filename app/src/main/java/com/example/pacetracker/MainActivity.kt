package com.example.pacetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import java.util.ArrayDeque

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var speedText: TextView
    private var currentPaceStr: String = "--:--"   // updated every GPS reading
    private val locationBuffer = ArrayDeque<Pair<Location, Long>>()
    private val bufferDurationMs = 5000L   // 5-second smoothing window
    private var smoothedSpeed = -1.0       // for exponential smoothing

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        speedText = findViewById(R.id.speedText)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        startLocationUpdates()  // your existing call

        // --- Start the foreground service ---
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(this, PaceTrackingService::class.java)
            startForegroundService(intent)
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1000
            )
            return
        }

        val request = LocationRequest.Builder(1000)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            mainLooper
        )
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
                locationBuffer.first().second < now - bufferDurationMs
            ) {
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
                        dist += pts[i - 1].first.distanceTo(pts[i].first)
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

            speedText.text = String.format(
                "Speed: %.2f km/h\nPace: %s",
                speedKmh,
                currentPaceStr
            )
        }
    }
}
