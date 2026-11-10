package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.*
import android.view.View
import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** 휴대폰 시작 화면과 주차 도구. 합성 받은편지함 조회는 디버그에서만 한다 */
class OwnerActivity : Activity() {
    private val demoMode =
        (BuildConfig.DEBUG || BuildConfig.CONTEST_DEMO) && BuildConfig.SYNTHETIC_BACKEND
    private val client = BackendClient()
    private val executor = Executors.newSingleThreadExecutor()
    private val timer = Handler(Looper.getMainLooper())
    private var showLegacyInbox = false
    private var loading = false
    private var items = JSONArray()
    private var error: String? = null
    private var opened: JSONObject? = null
    private var parkingArrival: JSONObject? = null
    private var arrivalUnavailable = false
    private var sensorStatus = "하강 감지 꺼짐"
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            timer.postDelayed(this, 5000)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            finish()
            return
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        render()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        render()
        timer.post(poll)
    }

    override fun onPause() {
        timer.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        timer.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun label(
        value: String,
        size: Float = 20f,
        color: Int = PleosPalette.ink(true),
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, dp(6), 0, dp(6))
        typeface = PleosTypography.face(bold)
    }

    private fun action(value: String, block: () -> Unit) =
        PhoneAccountUi(this).button(value, action = block).apply {
            minHeight = dp(64)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            }
        }

    private fun render() {
        val root = PhoneAccountUi(this).install()
        root.addView(label(getString(R.string.app_name), 18f, PleosPalette.action(true), true))
        root.addView(label("사건 근거와 사진 비교", 30f, bold = true))
        root.addView(action("사건 근거 보기") {
            startActivity(
                android.content.Intent(
                    this,
                    AccountEventsActivity::class.java
                )
            )
        })
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) root.addView(
            action(
                "내 차량 계정 연결"
            ) {
                startActivity(android.content.Intent(this, LoginActivity::class.java))
            })
        root.addView(action("층·기둥·주변 사진 비교") {
            startActivity(android.content.Intent(this, ParkingActivity::class.java))
        })
        if (!demoMode) root.addView(action("주차 위치 사진으로 남기기") {
            startActivity(android.content.Intent(this, PhotoParkingActivity::class.java))
        })
        if (demoMode) parkingArrival?.let { arrival ->
            val at = arrival.optDouble("parked_at")
            val age = System.currentTimeMillis() / 1000.0 - at
            val dismissed =
                getSharedPreferences("parking_relay", MODE_PRIVATE).getString("dismissed", null)
            val savedAt = ParkingPhotoStore(this).latest()?.optDouble("parked_at") ?: 0.0
            if (age in 0.0..86400.0 && dismissed != arrival.optString("id") && savedAt < at) {
                root.addView(label("주차 위치를 사진으로 남겨 주세요", 24f, bold = true))
                val date = SimpleDateFormat(
                    "M월 d일 HH:mm",
                    Locale.KOREAN
                ).format(Date((at * 1000).toLong()))
                root.addView(
                    label(
                        "개발 연결 · Pleos 에뮬레이터 $date 주차 감지\n층·기둥 번호가 보이게 촬영해 주세요.",
                        16f,
                        PleosPalette.muted(true)
                    )
                )
                if (arrivalUnavailable) root.addView(
                    label(
                        "연결 끊김 · 마지막 수신 안내",
                        15f,
                        Color.parseColor("#805000")
                    )
                )
                root.addView(action("주차 사진 저장하기") {
                    startActivity(android.content.Intent(this, PhotoParkingActivity::class.java))
                })
                root.addView(action("이 안내 닫기") {
                    getSharedPreferences("parking_relay", MODE_PRIVATE).edit()
                        .putString("dismissed", arrival.optString("id")).apply()
                    render()
                })
            }
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            root.addView(Switch(this).apply {
                text = "하강 신호로 주차 위치 안내"
                textSize = 18f
                minHeight = dp(60)
                setTextColor(PleosPalette.ink(true))
                typeface = PleosTypography.face(false)
                isChecked = ParkingMonitorService.running
                setOnCheckedChangeListener { _, enabled ->
                    if (enabled) enableMonitoring() else stopService(
                        android.content.Intent(
                            this@OwnerActivity,
                            ParkingMonitorService::class.java
                        )
                    )
                    timer.postDelayed({ if (!isDestroyed) render() }, 500)
                }
            })
            sensorStatus = ParkingMonitorService.status
            root.addView(label(sensorStatus, 15f, PleosPalette.muted(true)))
        }
        if (showLegacyInbox) error?.let {
            root.addView(
                label(
                    it,
                    18f,
                    Color.parseColor("#805000")
                )
            )
        }
        if (!demoMode) return
        root.addView(action(if (showLegacyInbox) "기존 서비스 시험 도구 접기" else "기존 서비스 시험 도구 펼치기") {
            showLegacyInbox = !showLegacyInbox
            render()
        })
        if (!showLegacyInbox) return
        root.addView(label("개발용 합성 알림함 · 제품 서비스와 별도", 16f, PleosPalette.muted(true)))
        root.addView(action("개발 시험 · 긴급 도움") {
            startActivity(android.content.Intent(this, EmergencyHelpActivity::class.java))
        })
        val item = opened
        if (item != null) {
            root.addView(label(item.getString("title"), 26f, bold = true))
            val valid = item.getBoolean("evidence_valid")
            root.addView(
                label(
                    if (valid) "센서 원본 확인됨" else "자료가 변경되어 내용을 확인할 수 없습니다",
                    20f,
                    if (valid) PleosPalette.action(true) else Color.parseColor("#805000")
                )
            )
            if (valid) {
                if (item.optString("kind") == "parking") root.addView(action("차량·충격 위치 상세 보기") {
                    startActivity(
                        android.content.Intent(this, IncidentReviewActivity::class.java)
                            .putExtra("event_id", item.getString("event_id"))
                    )
                })
                val location = item.getJSONObject("location")
                root.addView(label("시험 위치  ${location.getDouble("lat")}, ${location.getDouble("lon")}"))
                root.addView(label("시험 식별 번호  ${item.optString("plate", "미확인")}"))
                val video = item.optJSONObject("video")
                root.addView(
                    label(
                        if (video == null) "영상 미취득 · 실제 주변 차량 번호가 아닙니다" else "시험 패턴 영상 · 실제 촬영 아님",
                        18f,
                        PleosPalette.muted(true)
                    )
                )
                if (video?.optBoolean("integrity_valid") == true) root.addView(action("사건 전후 시험 영상 보기") {
                    startActivity(
                        android.content.Intent(this, VideoActivity::class.java)
                            .putExtra("event_id", item.getString("event_id"))
                    )
                })
            }
            root.addView(action("알림 목록으로") {
                opened = null
                render()
            })
        } else {
            val unread =
                (0 until items.length()).count { items.getJSONObject(it).isNull("read_at") }
            root.addView(label("읽지 않은 알림 $unread", 22f, bold = true))
            if (items.length() == 0) root.addView(
                label(
                    "아직 도착한 알림이 없습니다.",
                    20f,
                    PleosPalette.muted(true)
                )
            )
            for (i in 0 until items.length()) {
                val notification = items.getJSONObject(i)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(22), dp(18), dp(22), dp(18))
                    background = GradientDrawable().apply {
                        setColor(PleosPalette.surface(true))
                        cornerRadius = dp(12).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(16)
                        bottomMargin = dp(8)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { if (!loading) read(notification) }
                }
                val date = SimpleDateFormat(
                    "M월 d일  HH:mm",
                    Locale.KOREAN
                ).format(Date((notification.getDouble("created") * 1000).toLong()))
                card.addView(
                    label(
                        if (notification.isNull("read_at")) "● 새 알림" else "확인한 기록",
                        15f,
                        PleosPalette.action(true),
                        true
                    )
                )
                card.addView(label(notification.getString("title"), 23f, bold = true))
                card.addView(label(date, 16f, PleosPalette.muted(true)))
                card.addView(label("측정 자료 확인하기  ›", 18f, PleosPalette.action(true)))
                root.addView(card)
            }
            root.addView(action("새로 확인") { refresh() })
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) root.addView(
                action("차량 화면으로") { finish() })
        }
    }

    private fun refresh() {
        if (!demoMode) {
            if (!isDestroyed && sensorStatus != ParkingMonitorService.status) render()
            return
        }
        if (loading || isDestroyed) return
        loading = true
        val legacyRequested = showLegacyInbox
        executor.execute {
            val result = if (legacyRequested) try {
                JSONObject(client.request("/api/demo/owner/inbox")).getJSONArray("notifications")
            } catch (_: Exception) {
                null
            } else JSONArray()
            var arrivalFailed = false
            val arrival = if (demoMode) try {
                val raw = client.request("/api/dev/parking-arrival")
                if (raw.trim() == "null") null else JSONObject(raw)
            } catch (_: Exception) {
                arrivalFailed = true
                null
            } else null
            runOnUiThread {
                loading = false
                if (!isDestroyed) {
                    val message = if (result == null) "연결을 확인하세요. 아래 내용은 마지막 수신 기록입니다." else null
                    val changed =
                        (result != null && result.toString() != items.toString()) || error != message || sensorStatus != ParkingMonitorService.status || arrivalUnavailable != arrivalFailed || (!arrivalFailed && parkingArrival?.toString() != arrival?.toString())
                    arrivalUnavailable = arrivalFailed
                    if (!arrivalFailed) parkingArrival = arrival
                    if (result != null && legacyRequested == showLegacyInbox) {
                        items = result
                        opened?.let { current ->
                            opened = (0 until result.length()).map { result.getJSONObject(it) }
                                .firstOrNull { it.getString("id") == current.getString("id") }
                        }
                    }
                    if (legacyRequested == showLegacyInbox) error = message
                    if (changed) render()
                }
            }
        }
    }

    private fun read(item: JSONObject) {
        loading = true
        executor.execute {
            val receipt = try {
                JSONObject(
                    client.request(
                        "/api/demo/owner/notifications/${item.getString("id")}/read",
                        JSONObject()
                    )
                )
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                loading = false
                if (!isDestroyed) {
                    if (receipt != null) {
                        item.put("read_at", receipt.getDouble("read_at"))
                        opened = item
                        error = null
                    } else error = "읽음 상태를 저장하지 못했습니다. 다시 시도하세요."
                    render()
                }
            }
        }
    }

    private fun enableMonitoring() {
        if (ParkingPhotoStore(this).latest() == null) {
            error = "먼저 주차 위치와 사진을 저장해 주세요."
            render()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 71)
            return
        }
        try {
            startForegroundService(android.content.Intent(this, ParkingMonitorService::class.java))
        } catch (_: Exception) {
            error = "주차 안내를 시작하지 못했습니다. 다시 시도하세요."
            render()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) enableMonitoring()
            else error = "알림이 허용되지 않아 주차 안내 감지를 켜지 않았습니다."
            timer.postDelayed({ if (!isDestroyed) render() }, 500)
        }
    }
}
