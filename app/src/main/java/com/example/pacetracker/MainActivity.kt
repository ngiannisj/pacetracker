package com.example.pacetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var speedText: TextView

    private var lastLocation: Location? = null
    private var lastTime: Long = 0L
    private var currentPaceStr: String = "--:--"   // updated every GPS reading

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
            val currentTime = System.currentTimeMillis()

            val builtInSpeedMps = location.speed.toDouble()

            val manualSpeedMps: Double = if (lastLocation != null && lastTime > 0L) {
                val distanceMeters = lastLocation!!.distanceTo(location).toDouble()
                val timeSeconds = (currentTime - lastTime) / 1000.0
                if (timeSeconds > 0.0) distanceMeters / timeSeconds else 0.0
            } else {
                0.0
            }

            lastLocation = location
            lastTime = currentTime

            val speedMps = if (manualSpeedMps > 0.2) manualSpeedMps else builtInSpeedMps
            val speedKmh = speedMps * 3.6

            val paceMinPerKm = if (speedKmh > 0) 60.0 / speedKmh else Double.POSITIVE_INFINITY

            val paceStr = if (paceMinPerKm.isInfinite() || paceMinPerKm > 20) {
                "--:--"
            } else {
                val minutes = paceMinPerKm.toInt()
                val seconds = ((paceMinPerKm - minutes) * 60).toInt()
                String.format("%d:%02d min/km", minutes, seconds)
            }

            // Save for TTS to read later
            currentPaceStr = paceStr

            speedText.text = String.format(
                "Speed: %.2f km/h\nPace: %s",
                speedKmh,
                paceStr
            )
        }
    }
}
