package ai.teraq.roadguardian

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** 사용자가 켠 경우에만 알림을 띄우고 감시한다. 부팅 시 자동 시작은 없다 */
open class ParkingMonitorService : Service() {
    companion object {
        const val STOP = "ai.teraq.roadguardian.STOP_PARKING_MONITOR"

        @Volatile
        var running = false
            private set

        @Volatile
        var status = "주차 안내 감지 꺼짐"
            private set
    }

    private var monitor: PhoneDescentMonitor? = null
    private val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var cuePending = false
    private var stopping = false
    private var generation = 0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopping = true
            generation++
            status = "주차 안내 감지 꺼짐"
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "parking_monitor",
                "주차 안내 감지 상태",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                "parking_cue",
                "내 차 위치 안내",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val open = PendingIntent.getActivity(
            this,
            10,
            Intent(this, OwnerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            11,
            Intent(this, ParkingMonitorService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, "parking_monitor")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("R · 주차 안내 감지 중")
            .setContentText("하강 신호가 잡히면 저장된 내 차 위치를 알려드립니다")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "감지 끄기", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(
                41,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            else startForeground(41, notification)
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                status = "휴대폰에서 주차 안내를 켜 주세요"
                stopSelf()
                return START_NOT_STICKY
            }
            if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED)
            ) {
                status = "주차 안내를 켜려면 알림을 허용해 주세요"
                stopSelf()
                return START_NOT_STICKY
            }
            if (manager.getNotificationChannel("parking_monitor").importance == NotificationManager.IMPORTANCE_NONE || manager.getNotificationChannel(
                    "parking_cue"
                ).importance == NotificationManager.IMPORTANCE_NONE
            ) {
                status = "설정에서 주차 안내 알림을 켜 주세요"
                stopSelf()
                return START_NOT_STICKY
            }
            monitor = PhoneDescentMonitor(this) { showParkingCue() }
            status = monitor!!.start()
            running = monitor!!.running
            if (!running) stopSelf()
        } catch (_: Exception) {
            status = "주차 안내를 시작하지 못했습니다. 앱에서 다시 켜 주세요."
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun showParkingCue() {
        if (!running || stopping || cuePending) return
        cuePending = true
        val current = generation
        reader.execute {
            val record = try {
                readParkingRecord()
            } catch (_: Exception) {
                null
            }
            main.post {
                cuePending = false
                if (stopping || current != generation || !running || record == null) return@post
                publishParkingCue(record)
            }
        }
    }

    protected open fun readParkingRecord(): org.json.JSONObject? = ParkingPhotoStore(this).latest()
    protected open fun publishParkingCue(record: org.json.JSONObject) {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) {
            status = "알림이 꺼져 감지를 중지했습니다"
            stopSelf()
            return
        }
        try {
            ParkingReminder.show(this, record)
        } catch (_: SecurityException) {
            status = "알림 권한이 없어 감지를 중지했습니다"
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopping = true
        generation++
        reader.shutdownNow()
        monitor?.stop()
        running = false
        if (status.startsWith("주차 안내 감지 중")) status = "주차 안내 감지 꺼짐"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
