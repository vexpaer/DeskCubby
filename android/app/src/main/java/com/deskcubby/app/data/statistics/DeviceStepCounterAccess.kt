package com.deskcubby.app.data.statistics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class DeviceStepCounterAccess @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val appContext = context.applicationContext

    fun isAvailable(): Boolean =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED

    fun runtimePermission(): String? =
        Manifest.permission.ACTIVITY_RECOGNITION.takeIf {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        }

    suspend fun readCumulativeSteps(timeoutMillis: Long = SENSOR_READ_TIMEOUT_MILLIS): Long? {
        if (!isAvailable() || !hasPermission()) return null
        val manager = sensorManager ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val value = event.values.firstOrNull()
                            ?.takeIf(Float::isFinite)
                            ?.takeIf { it >= 0f }
                            ?.toLong()
                        manager.unregisterListener(this)
                        if (continuation.isActive) continuation.resume(value)
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                continuation.invokeOnCancellation {
                    manager.unregisterListener(listener)
                }
                val registered = manager.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    Handler(Looper.getMainLooper()),
                )
                if (!registered && continuation.isActive) continuation.resume(null)
            }
        }
    }

    companion object {
        private const val SENSOR_READ_TIMEOUT_MILLIS = 4_000L
    }
}
