package ai.teraq.roadguardian

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject

/** 플랫폼 주행 제한 상태를 읽는다. Activity를 distraction optimized로 선언하지 않는다 */
class VehicleUxMonitor(private val context: Context, private val changed: () -> Unit) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var closed = false
    private var current: CarUxRestrictions? = null
    private var status = "connecting"
    val restricted: Boolean get() = current?.isRequiresDistractionOptimization ?: true
    val videoRestricted: Boolean
        get() = current?.let {
            it.isRequiresDistractionOptimization || (it.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO) != 0
        } ?: true

    private fun update(value: CarUxRestrictions?, message: String) {
        if (closed) return
        current = value
        status = message
        changed()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) return
            try {
                manager =
                    car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                checkNotNull(manager).registerListener { update(it, "connected") }
                update(manager!!.currentCarUxRestrictions, "connected")
            } catch (e: Exception) {
                update(null, e.javaClass.simpleName)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            manager = null
            update(null, "disconnected")
        }
    }

    fun start() {
        if (closed || car != null) return
        try {
            car = Car.createCar(context, connection, Handler(Looper.getMainLooper()))
            car?.connect() ?: update(null, "unavailable")
        } catch (e: Exception) {
            update(null, e.javaClass.simpleName)
        }
    }

    fun snapshot() = JSONObject().put("status", status).put("restricted", restricted)
        .put("video_restricted", videoRestricted)
        .put("active_restrictions", current?.activeRestrictions ?: JSONObject.NULL)

    fun close() {
        if (closed) return
        closed = true
        status = "closed"
        try {
            manager?.unregisterListener()
        } catch (_: Exception) {
        }
        car?.disconnect()
        manager = null
        car = null
        current = null
    }
}
