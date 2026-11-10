package ai.teraq.roadguardian

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject

/** AAOS 실측 속도. 크루즈 목표 속도나 합성 시나리오 속도와 구분한다 */
class VehicleSpeedMonitor(private val context: Context) {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var closed = false
    private var status = "not_started"
    private var sequence = 0L
    private val signal = TimedSignal()
    private fun unavailable(reason: String) {
        status = reason
        val now = SystemClock.elapsedRealtime()
        signal.apply(++sequence, now, now, null, reason)
    }

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (closed || value.propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED) return
            val speed = value.value as? Float
            if (value.status != CarPropertyValue.STATUS_AVAILABLE || speed == null || !speed.isFinite()) {
                unavailable("unavailable")
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (signal.apply(
                    ++sequence,
                    value.timestamp / 1000000,
                    now,
                    speed.toString(),
                    null
                )
            ) status = "received"
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            if (!closed && propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) unavailable("property_error")
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) return
            try {
                manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                val registered = manager?.registerCallback(
                    callback,
                    VehiclePropertyIds.PERF_VEHICLE_SPEED,
                    2f
                ) == true
                if (!registered) unavailable("unsupported")
                else if (status == "connecting") status = "subscribed"
            } catch (e: Exception) {
                unavailable(e.javaClass.simpleName)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            manager = null
            if (!closed) unavailable("disconnected")
        }
    }

    fun start() {
        if (closed || car != null) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            status = "not_automotive"
            return
        }
        if (context.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            status = "permission_required"
            return
        }
        try {
            status = "connecting"
            car = Car.createCar(context, connection, Handler(Looper.getMainLooper()))
            car?.connect() ?: unavailable("unavailable")
        } catch (e: Exception) {
            unavailable(e.javaClass.simpleName)
        }
    }

    fun snapshot(): JSONObject {
        val now = SystemClock.elapsedRealtime()
        val speed = signal.value?.toDoubleOrNull()
        return JSONObject().put("source", "aaos_perf_vehicle_speed").put("status", status)
            .put("meters_per_second", speed ?: JSONObject.NULL)
            .put("kilometers_per_hour", speed?.times(3.6) ?: JSONObject.NULL)
            .put("fresh", signal.fresh(now))
            .put("age_ms", if (signal.at < 0) JSONObject.NULL else now - signal.at)
            .put("synthetic_fallback", false)
    }

    fun close() {
        if (closed) return
        closed = true
        unavailable("closed")
        try {
            manager?.unregisterCallback(callback)
        } catch (_: Exception) {
        }
        car?.disconnect()
        manager = null
        car = null
    }
}
