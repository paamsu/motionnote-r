package ai.teraq.roadguardian

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject

/** SDK 값 읽기 전용. 값이 없거나 오래되면 에어백 미전개로 보지 않는다 */
class PleosAirbagMonitor(private val vehicle: Vehicle) {
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var status = "not_requested"
    private var failureDetail: String? = null
    private fun failed(error: Exception) {
        status = "failed:${error.javaClass.simpleName}"
        failureDetail = if (BuildConfig.DEBUG) error.message?.take(512) else null
    }

    private var areas = emptyList<SeatArea>()
    private val samples = linkedMapOf<String, TimedSignal>()
    private var sequence = 0L
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            val request = ++sequence
            val started = SystemClock.elapsedRealtime()
            areas.forEach { area ->
                val key = area.values.joinToString("+") { it.name }
                fun apply(value: Boolean?, error: String? = null) {
                    handler.post {
                        if (!closed) samples.getOrPut(key) { TimedSignal() }.apply(
                            request,
                            started,
                            SystemClock.elapsedRealtime(),
                            value?.toString(),
                            error
                        )
                    }
                }
                try {
                    vehicle.getSafety().hasSeatAirbagBeenDeployed(
                        area,
                        onComplete = { apply(it) },
                        onFailed = { apply(null, it.javaClass.simpleName) })
                } catch (error: Exception) {
                    apply(null, error.javaClass.simpleName)
                }
            }
            handler.postDelayed(this, 2000)
        }
    }

    fun start() {
        if (closed || status != "not_requested") return
        status = "capability_pending"
        handler.postDelayed({
            if (!closed && status == "capability_pending") status = "capability_timeout"
        }, 5000)
        try {
            vehicle.getSafety().checkAirbagDeployedCapabilities(
                onComplete = { capability, supported ->
                    handler.post {
                        if (!closed) {
                            status = "capability:${capability.accessType}"
                            if (capability.accessType in listOf(
                                    AccessType.READ,
                                    AccessType.READ_WRITE
                                )
                            ) {
                                areas = supported.toList()
                                if (areas.isEmpty()) status = "no_supported_area" else handler.post(
                                    poll
                                )
                            }
                        }
                    }
                }, onFailed = { error -> handler.post { if (!closed) failed(error) } })
        } catch (error: Exception) {
            failed(error)
        }
    }

    fun snapshot(): JSONObject {
        val now = SystemClock.elapsedRealtime()
        val values = JSONObject()
        areas.forEach { area ->
            val key = area.values.joinToString("+") { it.name }
            val signal = samples[key]
            val fresh = signal?.fresh(now) == true
            values.put(
                key,
                JSONObject().put(
                    "deployed",
                    if (fresh) signal!!.value == "true" else JSONObject.NULL
                )
                    .put("fresh", fresh).put(
                        "age_ms",
                        signal?.at?.takeIf { it >= 0 }?.let { now - it } ?: JSONObject.NULL)
                    .put("error", signal?.error ?: JSONObject.NULL))
        }
        return JSONObject().put("source", "pleos_vehicle_sdk").put("status", status)
            .put("seats", values)
            .put("debug_failure", failureDetail ?: JSONObject.NULL)
            .put("synthetic_fallback", false).put("crash_confirmed", false)
    }

    fun summary(): String {
        val now = SystemClock.elapsedRealtime()
        val fresh = samples.values.filter { it.fresh(now) }
        return when {
            fresh.any { it.value == "true" } -> "에어백 전개 응답 있음 · 사고 여부는 추가 확인 필요"
            areas.isNotEmpty() && fresh.size == areas.size -> "조회한 좌석에서 미전개 응답 · 무사고를 뜻하지 않음"
            else -> "에어백 상태 미확인 · 지원 상태와 연결 확인 필요"
        }
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        areas = emptyList()
        samples.clear()
        status = "closed"
    }
}
