package ai.teraq.roadguardian

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** 보이는 포그라운드 서비스가 센서를 구독한다. 원시 데이터는 올리지 않는다 */
class PhoneDescentMonitor(context: Context, private val onCue: () -> Unit) : SensorEventListener {
    var running = false
        private set
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val types =
        listOf(Sensor.TYPE_PRESSURE, Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION)
    private val detector = DescentCue()
    private var gravity: FloatArray? = null
    private var linear: FloatArray? = null
    private var gravityAt = 0L
    private var linearAt = 0L
    private var lastSample = 0L
    private val unreliableTypes = mutableSetOf<Int>()
    fun start(): String {
        stop()
        val sensors = types.map { manager.getDefaultSensor(it) }
        if (sensors.any { it == null }) return "이 기기에서는 하강 감지에 필요한 센서를 사용할 수 없습니다."
        for (sensor in sensors) {
            if (!manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)) {
                stop()
                return "휴대폰 센서를 시작하지 못했습니다."
            }
        }
        running = true
        return "주차 안내 감지 중 · 알림에서 끌 수 있습니다"
    }

    fun stop() {
        running = false
        manager.unregisterListener(this)
        detector.reset()
        gravity = null
        linear = null
        gravityAt = 0
        linearAt = 0
        lastSample = 0
        unreliableTypes.clear()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val type = sensor?.type ?: return
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            unreliableTypes.add(type)
            detector.reset()
            gravity = null
            linear = null
        } else unreliableTypes.remove(type)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || event.sensor.type in unreliableTypes) {
            detector.reset()
            gravity = null
            linear = null
            return
        }
        if (event.values.isEmpty()) {
            detector.reset()
            return
        }
        val now = event.timestamp / 1_000_000
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravity = event.values.clone()
                gravityAt = now
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                linear = event.values.clone()
                linearAt = now
            }

            Sensor.TYPE_PRESSURE -> {
                if (now - lastSample < 100) return
                val g = gravity ?: return
                val l = linear ?: return
                if (now - gravityAt !in 0..1000 || now - linearAt !in 0..1000) {
                    detector.reset()
                    return
                }
                lastSample = now
                if (detector.add(
                        now,
                        event.values[0].toDouble(),
                        DescentCue.vertical(l, g)
                    )
                ) onCue()
            }
        }
    }
}
