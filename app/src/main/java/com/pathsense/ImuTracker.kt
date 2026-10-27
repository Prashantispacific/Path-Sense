package com.pathsense

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * High-performance IMU tracker handling Linear Acceleration step-phase detection
 * and Gyroscope rotation integration for camera frame derotation.
 */
class ImuTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Ring buffer for linear acceleration samples: Pair<TimestampNs, AccelMagnitude>
    private val accWindowNs = 300_000_000L // 300 ms window
    private val accSamples = ArrayDeque<Pair<Long, Float>>()

    // Ring buffer for gyro samples: (TimestampNs, wx, wy, wz)
    data class GyroSample(val timestampNs: Long, val wx: Float, val wy: Float, val wz: Float)
    private val gyroSamples = ArrayDeque<GyroSample>()
    private val maxGyroWindowNs = 1_000_000_000L // 1 second buffer

    // Step variance threshold: below this, user is considered stationary
    var stepVarianceThreshold: Float = 0.12f

    @Volatile
    private var isRegistered = false

    fun start() {
        if (isRegistered) return
        linearAccSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        isRegistered = true
    }

    fun stop() {
        if (!isRegistered) return
        sensorManager.unregisterListener(this)
        synchronized(this) {
            accSamples.clear()
            gyroSamples.clear()
        }
        isRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val timestamp = event.timestamp

        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val mag = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()

                synchronized(accSamples) {
                    accSamples.addLast(Pair(timestamp, mag))
                    // Prune older than 300ms
                    val cutoff = timestamp - accWindowNs
                    while (accSamples.isNotEmpty() && accSamples.first.first < cutoff) {
                        accSamples.removeFirst()
                    }
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                val wx = event.values[0]
                val wy = event.values[1]
                val wz = event.values[2]

                synchronized(gyroSamples) {
                    gyroSamples.addLast(GyroSample(timestamp, wx, wy, wz))
                    val cutoff = timestamp - maxGyroWindowNs
                    while (gyroSamples.isNotEmpty() && gyroSamples.first.timestampNs < cutoff) {
                        gyroSamples.removeFirst()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op for high frequency inertial sampling
    }

    /**
     * Computes the rolling variance of linear acceleration over the last 300ms.
     * Returns true if user is stationary (variance < threshold or no data).
     */
    fun isStationary(): Boolean {
        var count = 0
        var sum = 0.0
        var sumSq = 0.0

        synchronized(accSamples) {
            if (accSamples.size < 5) return false // Not enough samples yet to declare stationary
            for (sample in accSamples) {
                val v = sample.second.toDouble()
                sum += v
                sumSq += v * v
                count++
            }
        }

        if (count < 2) return false
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return variance < stepVarianceThreshold
    }

    /**
     * Integrates angular velocity (wx, wy) between two frame timestamps to estimate
     * pixel shift (dx_rot, dy_rot) induced by hand pitch and roll jitter.
     *
     * dx_rot = wy * dt * focalLengthPx (horizontal camera shift caused by yaw/roll)
     * dy_rot = wx * dt * focalLengthPx (vertical camera shift caused by pitch)
     */
    fun getRotationalShift(prevTimestampNs: Long, currTimestampNs: Long, focalLengthPx: Float): Pair<Float, Float> {
        if (currTimestampNs <= prevTimestampNs) return Pair(0f, 0f)

        var sumWx = 0.0
        var sumWy = 0.0
        var sampleCount = 0

        synchronized(gyroSamples) {
            for (sample in gyroSamples) {
                if (sample.timestampNs in (prevTimestampNs + 1)..currTimestampNs) {
                    sumWx += sample.wx
                    sumWy += sample.wy
                    sampleCount++
                }
            }
        }

        val dt = (currTimestampNs - prevTimestampNs) * 1e-9f
        if (sampleCount == 0 || dt <= 0f) {
            return Pair(0f, 0f)
        }

        val avgWx = (sumWx / sampleCount).toFloat()
        val avgWy = (sumWy / sampleCount).toFloat()

        val dxRot = avgWy * dt * focalLengthPx
        val dyRot = avgWx * dt * focalLengthPx

        return Pair(dxRot, dyRot)
    }
}
