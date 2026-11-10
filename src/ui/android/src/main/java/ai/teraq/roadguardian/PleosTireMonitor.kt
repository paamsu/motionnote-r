package ai.teraq.roadguardian

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject

/** SDK 공기압·기준값 읽기 전용. 단위나 주행 가능 여부를 추정하지 않는다 */
class PleosTireMonitor(private val vehicle: Vehicle, private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var generation = 0L
    private var sequence = 0L
    private val channels = listOf("pressure", "critical_threshold")
    private val status = linkedMapOf<String, String>()
    private val areas = linkedMapOf<String, List<WheelArea>>()
    private val reportedAreas = linkedMapOf<String, List<WheelArea>>()
    private val signals = linkedMapOf<String, TimedSignal>()
    private fun key(area: WheelArea) =
        area.values.filter { it.name != "UNKNOWN" }.singleOrNull()?.name

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            val epoch = generation
            val request = ++sequence
            val started = SystemClock.elapsedRealtime()
            for (channel in channels) for (area in areas[channel].orEmpty()) {
                val wheel = key(area) ?: continue
                fun accept(value: Float?, error: String? = null) {
                    handler.post {
                        if (running && generation == epoch) {
                            val good = value?.takeIf { it.isFinite() && it >= 0 }
                            signals.getOrPut("$channel:$wheel") { TimedSignal() }.apply(
                                request,
                                started,
                                SystemClock.elapsedRealtime(),
                                good?.toString(),
                                error ?: if (good == null) "invalid_or_missing_value" else null
                            )
                        }
                    }
                }
                try {
                    if (channel == "pressure") vehicle.getTire().getTirePressure(
                        area,
                        onComplete = { accept(it) },
                        onFailed = { accept(null, it.javaClass.simpleName) })
                    else vehicle.getTire().getLowTirePressure(
                        area,
                        onComplete = { accept(it) },
                        onFailed = { accept(null, it.javaClass.simpleName) })
                } catch (error: Exception) {
                    accept(null, error.javaClass.simpleName)
                }
            }
            handler.postDelayed(this, 3000)
        }
    }

    fun start() {
        if (running) return
        running = true
        val epoch = ++generation
        for (channel in channels) {
            if (channel == "pressure" && context.checkSelfPermission("pleos.car.permission.PRIVATE_CAR_SAFETY") != PackageManager.PERMISSION_GRANTED) {
                status[channel] = "permission_required:PRIVATE_CAR_SAFETY"
                continue
            }
            status[channel] = "capability_pending"
            handler.postDelayed({
                if (running && generation == epoch && status[channel] == "capability_pending") status[channel] =
                    "capability_timeout"
            }, 5000)
            fun failure(error: Exception) {
                handler.post {
                    if (running && generation == epoch) status[channel] =
                        "failed:${error.javaClass.simpleName}:${
                            if (BuildConfig.DEBUG) error.message?.take(160) else ""
                        }"
                }
            }

            val complete: (ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig, List<WheelArea>) -> Unit =
                { capability, supported ->
                    handler.post {
                        if (running && generation == epoch) {
                            status[channel] = "capability:${capability.accessType}"
                            reportedAreas[channel] = supported.toList()
                            if (capability.accessType in listOf(
                                    AccessType.READ,
                                    AccessType.READ_WRITE
                                )
                            ) {
                                areas[channel] = supported.filter { key(it) in WHEELS }
                                if (areas[channel].isNullOrEmpty()) status[channel] =
                                    "no_individual_wheel_area"
                            }
                        }
                    }
                }
            try {
                if (channel == "pressure") vehicle.getTire().checkTirePressureCapabilities(
                    onComplete = complete,
                    onFailed = { failure(it) })
                else vehicle.getTire().checkLowTirePressureCapabilities(
                    onComplete = complete,
                    onFailed = { failure(it) })
            } catch (error: Exception) {
                failure(error)
            }
        }
        handler.post(poll)
    }

    fun snapshot(): JSONObject {
        val now = SystemClock.elapsedRealtime()
        val wheels = JSONObject()
        for (wheel in WHEELS) {
            val item = JSONObject()
            for (channel in channels) {
                val signal = signals["$channel:$wheel"]
                val fresh = running && signal?.fresh(now) == true
                item.put(
                    channel,
                    JSONObject().put(
                        "value",
                        if (fresh) signal!!.value.toDouble() else JSONObject.NULL
                    )
                        .put("fresh", fresh).put(
                            "age_ms",
                            signal?.at?.takeIf { it >= 0 }?.let { now - it } ?: JSONObject.NULL
                        )
                        .put("error", signal?.error ?: JSONObject.NULL)
                )
            }
            wheels.put(wheel, item)
        }
        val reported = JSONObject()
        channels.forEach { channel ->
            reported.put(
                channel,
                org.json.JSONArray(
                    reportedAreas[channel].orEmpty()
                        .map { area -> area.values.joinToString("+") { it.name } })
            )
        }
        return JSONObject().put("source", "pleos_vehicle_sdk").put("running", running)
            .put("declared_areas", reported)
            .put("pressure_status", status["pressure"] ?: "not_requested")
            .put("threshold_status", status["critical_threshold"] ?: "not_requested")
            .put("unit_status", "unconfirmed_sdk_contract")
            .put("safety_assessment", "not_performed")
            .put("synthetic_fallback", false).put("wheels", wheels)
    }

    fun stop() {
        running = false
        generation++
        handler.removeCallbacksAndMessages(null)
        areas.clear()
        reportedAreas.clear()
        signals.clear()
        channels.forEach { status[it] = "stopped" }
    }

    companion object {
        val WHEELS = listOf("LEFT_FRONT", "RIGHT_FRONT", "LEFT_REAR", "RIGHT_REAR")
    }
}
