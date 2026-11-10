package ai.teraq.roadguardian

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONArray
import java.util.concurrent.Executors

/** 공모전 시연 세션을 켰을 때만 돈다. 화면과 별도로 사건 조회를 맡는다 */
class DemoEventMonitorService : Service() {
    companion object {
        const val STOP = "ai.teraq.roadguardian.STOP_EVENT_SESSION"
        private const val SESSION_MS = 30 * 60 * 1000L

        @Volatile
        var running = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val client = BackendClient()
    private val sharedWorker = Executors.newSingleThreadExecutor()
    private var sharedLoading = false
    private var sharedGeneration = 0L
    private val notices = DemoEventMonitorState()
    private val sharedConsumed = mutableSetOf<String>()
    private var stopped = false
    private var deadline = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    private val poll = object : Runnable {
        override fun run() {
            if (stopped) return
            if (SystemClock.elapsedRealtime() >= deadline || !DriverEventNotification.available(this@DemoEventMonitorService)) {
                stopSelf()
                return
            }
            val started = SystemClock.elapsedRealtime()
            worker.execute {
                val rows = runCatching { JSONArray(client.request("/api/events")) }.getOrNull()
                main.post {
                    if (stopped) return@post
                    val now = SystemClock.elapsedRealtime()
                    if (now >= deadline) {
                        stopSelf()
                        return@post
                    }
                    val local = notices.receive(
                        rows,
                        started,
                        now,
                        DriverEventNotification.available(this@DemoEventMonitorService)
                    )
                    local?.let {
                        sharedGeneration++
                        if (!ContestNavigationActivity.showOverlay(it))
                            DriverEventNotification.show(this@DemoEventMonitorService, it)
                    }
                    if (local == null && rows != null && now - started in 0L..4999L) pollShared()
                    main.postDelayed(this, 5000)
                }
            }
        }
    }

    private fun pollShared() {
        if (stopped || sharedLoading) return
        sharedLoading = true
        val ticket = sharedGeneration
        val started = SystemClock.elapsedRealtime()
        sharedWorker.execute {
            val feed =
                runCatching { org.json.JSONObject(client.request("/api/demo/approaching-hazards")) }.getOrNull()
            main.post {
                sharedLoading = false
                val now = SystemClock.elapsedRealtime()
                if (stopped || now >= deadline || ticket != sharedGeneration || now - started !in 0L..4999L || feed == null) return@post
                if (!DriverEventNotification.available(this)) return@post
                val checked = SharedRoadNotice.checked(feed, System.currentTimeMillis() / 1000.0)
                    ?: return@post
                if (checked.key !in sharedConsumed && ContestNavigationActivity.showSharedOverlay(
                        feed
                    )
                ) sharedConsumed.add(checked.key)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        if (!(BuildConfig.DEBUG || BuildConfig.CONTEST_DEMO) || !BuildConfig.SYNTHETIC_BACKEND || !DriverEventNotification.available(
                this
            )
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "event_session",
                "이벤트 안내 상태",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        if (manager.getNotificationChannel("event_session").importance == NotificationManager.IMPORTANCE_NONE) {
            stopSelf()
            return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(
            this,
            354,
            Intent(this, DemoEventMonitorService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification =
            Notification.Builder(this, "event_session").setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("이벤트 안내 켜짐").setContentText("다른 앱 사용 중에도 안내 · 30분 후 종료")
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "안내 끄기", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(
                354,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            else startForeground(354, notification)
            running = true
            deadline = SystemClock.elapsedRealtime() + SESSION_MS
            main.post(poll)
        } catch (_: RuntimeException) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        running = false
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        sharedWorker.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

/** 응답이 없거나 늦으면 기준점을 다시 잡는다. 다시 연결돼도 지난 사건을 다시 띄우지 않는다 */
class DemoEventMonitorState {
    private val notices = DriverEventNoticeState()
    private val consumed = mutableSetOf<String>()
    fun invalidate() {
        notices.invalidate()
    }

    fun receive(
        rows: JSONArray?,
        started: Long,
        now: Long,
        notificationAllowed: Boolean
    ): org.json.JSONObject? {
        if (rows == null || now - started !in 0L..4999L) {
            notices.invalidate()
            return null
        }
        notices.accept(rows, now)
        val event = notices.takeCurrent(now) ?: return null
        val key = DriverEventNotification.identity(event) ?: return null
        if (!consumed.add(key)) return null
        // 알림 권한이 다시 켜져도 지난 사건을 다시 띄우지 않는다
        // 일반 알림의 주행 중 표시 여부는 시스템 UI가 정한다. 화면·영상 주행 제한으로 알림을 미리 버리지 않는다
        return event.takeIf { notificationAllowed }
    }
}
