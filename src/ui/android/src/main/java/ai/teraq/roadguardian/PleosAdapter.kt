package ai.teraq.roadguardian

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType

/** Pleos 차량 서비스 연결. 연결에 실패하면 실패로 표시하고 합성 값으로 대신하지 않는다 */
class PleosAdapter(
    context: Context,
    private val report: (String) -> Unit,
    private val gearReport: (String?, Long) -> Unit
) {
    private val vehicle = Vehicle(context)
    private val appContext = context.applicationContext

    // 기어는 AAOS 표준 속성 GEAR_SELECTION으로 읽는다. Pleos Gear API는 SDK 2.3.2에서 폐기 예정
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private val airbags = PleosAirbagMonitor(vehicle)
    private val tires = PleosTireMonitor(vehicle, context.applicationContext)
    fun startTireMonitoring() {
        if (!closed) tires.start()
    }

    fun stopTireMonitoring() {
        tires.stop()
    }

    fun tireSnapshot() = tires.snapshot()
    fun airbagSummary() = airbags.summary()
    private val speedMonitor = VehicleSpeedMonitor(context)
    fun startSpeedMonitoring() {
        speedMonitor.start()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var received = false
    private var gearRequest = 0L
    private val gearSignal = TimedSignal()
    private var gearValue: String? = null
    private var gearAt = -1L
    private var gearStatus = "기어 응답 대기"
    private var odometerStatus = "주행거리 응답 대기"
    private var initialization = "not_started"
    private var lastMessage = ""
    private var wiperStatus = "not_requested"
    private val wipers = linkedMapOf<String, TimedSignal>()
    private var wiperAreas = emptyList<ai.pleos.playground.vehicle.api.model.area.WindowArea>()
    private var wiperRequest = 0L
    private val wiperPoll = object : Runnable {
        override fun run() {
            if (closed) return
            val request = ++wiperRequest
            val started = android.os.SystemClock.elapsedRealtime()
            wiperAreas.forEach { area ->
                val key = area.values.joinToString("+") { it.name }
                fun apply(value: String?, error: String? = null) {
                    handler.post {
                        if (!closed) wipers.getOrPut(key) { TimedSignal() }.apply(
                            request, started,
                            android.os.SystemClock.elapsedRealtime(), value, error
                        )
                    }
                }
                try {
                    vehicle.getWiper().getCurrentWiperState(
                        area,
                        onComplete = { value -> apply(value?.name) },
                        onFailed = { error -> apply(null, error.javaClass.simpleName) })
                } catch (error: Exception) {
                    apply(null, error.javaClass.simpleName)
                }
            }
            handler.postDelayed(this, 2000)
        }
    }

    fun diagnosticSnapshot(): org.json.JSONObject {
        val now = android.os.SystemClock.elapsedRealtime()
        val states = org.json.JSONObject()
        wipers.forEach { (area, sample) ->
            states.put(
                area, org.json.JSONObject()
                    .put("state", sample.value ?: org.json.JSONObject.NULL)
                    .put("age_ms", now - sample.at)
                    .put("fresh", sample.fresh(now))
                    .put("error", sample.error ?: org.json.JSONObject.NULL)
            )
        }
        return org.json.JSONObject().put("sdk_version", BuildConfig.PLEOS_SDK_VERSION)
            .put("airbags", airbags.snapshot())
            .put("tires", tires.snapshot())
            .put("vehicle_speed", speedMonitor.snapshot())
            .put("wiper_status", wiperStatus).put("wipers", states)
            .put("initialization", initialization).put("odometer_status", odometerStatus)
            .put("gear_status", gearStatus).put("gear", gearValue ?: org.json.JSONObject.NULL)
            .put("fresh_gear_received", gearValue != null && gearAt >= 0 && now - gearAt in 0..4999)
            .put("gear_age_ms", if (gearAt < 0) org.json.JSONObject.NULL else now - gearAt)
            .put("synthetic_fallback", false)
    }

    private fun publish() {
        val message = "$odometerStatus\n$gearStatus"
        if (message != lastMessage) {
            lastMessage = message
            report(message)
        }
    }

    private val gearPoll = object : Runnable {
        override fun run() {
            if (closed) return
            val request = ++gearRequest
            val started = android.os.SystemClock.elapsedRealtime()
            fun apply(value: String?, failure: String? = null) {
                handler.post {
                    if (!closed && gearSignal.apply(
                            request,
                            started,
                            android.os.SystemClock.elapsedRealtime(),
                            value,
                            failure
                        )
                    ) {
                        gearValue = gearSignal.value
                        gearAt = gearSignal.at
                        gearStatus =
                            if (value != null) "기어 응답: $value" else "기어 미확인: ${failure ?: "자료 없음"}"
                        gearReport(gearValue, gearAt)
                        publish()
                    }
                }
            }
            try {
                val manager = propertyManager
                if (manager == null) apply(null, "car_service_not_ready")
                else apply(gearName(manager.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)))
            } catch (error: Exception) {
                apply(null, error.javaClass.simpleName)
            }
            handler.postDelayed(this, 2000)
        }
    }

    /** AAOS VehicleGear 값을 기존 판정 코드가 쓰는 이름(GEAR_PARK 등)으로 바꾼다 */
    private fun gearName(value: Int) = when (value) {
        1 -> "GEAR_NEUTRAL"
        2 -> "GEAR_REVERSE"
        4 -> "GEAR_PARK"
        8 -> "GEAR_DRIVE"
        else -> "GEAR_UNKNOWN"
    }

    private val carConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) return
            propertyManager = try {
                car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            } catch (_: Exception) {
                null
            }
            handler.removeCallbacks(gearPoll)
            handler.post(gearPoll)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            propertyManager = null
        }
    }

    private fun connectCarService() {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            gearStatus = "기어 미확인: not_automotive"
            publish()
            return
        }
        try {
            car = Car.createCar(appContext, carConnection, handler)
            car?.connect()
        } catch (error: Exception) {
            gearStatus = "기어 미확인: ${error.javaClass.simpleName}"
            publish()
        }
    }

    fun start() {
        if (closed) return
        speedMonitor.start()
        initialization = "attempted"
        try {
            vehicle.initialize()
            initialization = "initialize_returned"
            connectCarService()
            readWipers()
            airbags.start()
            vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities(
                onComplete = { capability ->
                    if (closed) return@checkCurrentDistanceDrivenCapabilities
                    received = true
                    update("Pleos 응답: ${capability.accessType}")
                    if (capability.accessType !in listOf(
                            AccessType.READ,
                            AccessType.READ_WRITE
                        )
                    ) return@checkCurrentDistanceDrivenCapabilities
                    vehicle.getOdometer().getCurrentDistanceDriven(
                        onComplete = { distance -> update(if (distance == null) "주행거리 자료 없음" else "Pleos 주행거리: $distance") },
                        onFailed = { error -> update("Pleos 조회 실패: ${error.javaClass.simpleName}") }
                    )
                },
                onFailed = { error ->
                    received = true
                    update("Pleos 미연결: ${error.javaClass.simpleName}")
                }
            )
            handler.postDelayed({ if (!received) update("Pleos 서비스 응답 없음 · 전용 환경 필요") }, 5000)
        } catch (error: Exception) {
            initialization = "failed:${error.javaClass.simpleName}"
            update("Pleos 미연결: ${error.javaClass.simpleName}")
        }
    }

    private fun update(value: String) {
        handler.post {
            if (!closed) {
                odometerStatus = value
                publish()
            }
        }
    }

    private fun readWipers() {
        wiperStatus = "capability_pending"
        try {
            vehicle.getWiper().checkWiperStateCapabilities(
                onComplete = { capability, areas ->
                    handler.post {
                        if (!closed) {
                            wiperStatus = "capability:${capability.accessType}"
                            if (capability.accessType in listOf(
                                    AccessType.READ,
                                    AccessType.READ_WRITE
                                )
                            ) {
                                if (areas.isEmpty()) wiperStatus = "no_supported_area"
                                wiperAreas = areas.keys.toList()
                                if (wiperAreas.isNotEmpty()) handler.post(wiperPoll)
                            }
                        }
                    }
                },
                onFailed = { error ->
                    handler.post {
                        if (!closed) wiperStatus = "capability_failed:${error.javaClass.simpleName}"
                    }
                })
        } catch (error: Exception) {
            wiperStatus = "failed:${error.javaClass.simpleName}"
        }
    }

    fun close(notifyGear: Boolean = true) {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        gearValue = null
        gearAt = -1L
        gearStatus = "기어 연결 종료"
        odometerStatus = "차량 연결 종료"
        initialization = "closed"
        wipers.clear()
        wiperStatus = "closed"
        airbags.close()
        tires.stop()
        speedMonitor.close()
        propertyManager = null
        try {
            car?.disconnect()
        } catch (_: Exception) {
        }
        car = null
        try {
            vehicle.release()
        } finally {
            if (notifyGear) {
                publish()
                gearReport(null, -1L)
            }
        }
    }
}
