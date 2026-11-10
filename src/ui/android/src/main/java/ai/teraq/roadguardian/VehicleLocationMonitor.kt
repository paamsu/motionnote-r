package ai.teraq.roadguardian

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

/** 포그라운드에서만 GPS를 구독한다. 마지막 위치를 보관하거나 올리지 않는다 */
class VehicleLocationMonitor(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var sample: VehicleLocationSample? = null
    var status = "위치 권한 확인 필요"
        private set
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            @Suppress("DEPRECATION")
            val next = VehicleLocationSample(
                location.latitude, location.longitude,
                if (location.hasAccuracy()) location.accuracy else Float.NaN,
                location.elapsedRealtimeNanos / 1000000, location.isFromMockProvider
            )
            sample = if (next.usable(SystemClock.elapsedRealtime())) next else null
            status =
                if (sample == null) "위치 정확도 또는 시각 확인 필요" else if (next.mock) "시험 위치 수신" else "기기 위치 수신"
        }

        override fun onProviderDisabled(provider: String) {
            sample = null
            status = "기기 위치 서비스 꺼짐"
        }

        override fun onProviderEnabled(provider: String) {
            status = "위치 응답 대기"
        }

        @Deprecated("Legacy location callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    fun current(): VehicleLocationSample? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return sample?.takeIf { it.usable(SystemClock.elapsedRealtime()) }
    }

    fun start() {
        stop()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status = "위치 권한 확인 필요"
            return
        }
        try {
            if (manager == null || !manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                status = "GPS 제공자 사용 불가"
                return
            }
            status =
                if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) "위치 응답 대기" else "기기 위치 서비스 꺼짐"
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L,
                0f,
                listener,
                Looper.getMainLooper()
            )
        } catch (_: Exception) {
            status = "기기 위치 조회 불가"
        }
    }

    fun stop() {
        try {
            manager?.removeUpdates(listener)
        } catch (_: Exception) {
        }
        sample = null
    }
}
