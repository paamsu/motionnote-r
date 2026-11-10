package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** 정차 중에 직접 실행하는 합성 응급 시연. 사람 상태 감지나 기관 호출은 하지 않는다 */
class EmergencyDemoActivity : Activity() {
    private val timer = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var ui: PhoneAccountUi
    private lateinit var body: LinearLayout
    private lateinit var title: TextView
    private lateinit var countdown: TextView
    private lateinit var saved: TextView
    private var ux: VehicleUxMonitor? = null
    private var origin = 0L
    private var events = JSONArray()
    private var state = "ready"
    private var key = UUID.randomUUID().toString()
    private var foreground = false
    private var finalPayload: JSONObject? = null
    private var saving = false
    private var eventId = ""
    private var receipt = ""
    private var retry: android.widget.Button? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!foreground || state != "waiting") return
            if (ux?.restricted == true) {
                interrupt()
                return
            }
            observe("heartbeat")
            if (state == "waiting") timer.postDelayed(this, 500)
        }
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        if (!BuildConfig.SYNTHETIC_BACKEND) {
            finish()
            return
        }
        ui = PhoneAccountUi(this, intent.getBooleanExtra("light", true))
        body = ui.install()
        val stored = records()
        val selectedKey = bundle?.getString("record_key")
        val selected =
            if (selectedKey != null) stored.firstOrNull { it.nameWithoutExtension == selectedKey } else stored.firstOrNull()
        if (selected != null) {
            if (!restore(selected)) state = "record_unavailable"
        } else if (selectedKey != null) state = "record_unavailable"
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
            ux =
                VehicleUxMonitor(this) { if (state == "waiting" && ux?.restricted == true) interrupt() }
            ux?.start()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        if (state == "waiting") interrupt()
        timer.removeCallbacks(tick)
        super.onPause()
    }

    override fun onSaveInstanceState(out: Bundle) {
        if (state != "ready") out.putString("record_key", key)
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        timer.removeCallbacks(tick)
        ux?.close()
        worker.shutdown()
        super.onDestroy()
    }

    private fun payload(at: Double) = JSONObject().put(
        "timeline", JSONObject()
            .put("schema", "road-guardian.emergency-timeline.v1")
            .put("source", "authored_synthetic")
            .put("profile", "authored-unresponsive-timeline-v2").put("timeout_seconds", 30)
            .put("heartbeat_max_gap_seconds", 2).put("events", JSONArray(events.toString()))
    )
        .put("as_of", at).put("request_key", key)

    private fun persist(at: Double) {
        val dir = File(filesDir, "emergency-demo").apply { mkdirs() }
        val file = File(dir, "$key.json")
        val temp = File(dir, "$key.tmp")
        temp.writeText(JSONObject().put("state", state).put("payload", payload(at)).toString(2))
        check(temp.renameTo(file))
    }

    private fun records(): List<File> = File(filesDir, "emergency-demo").listFiles()?.filter {
        it.extension == "json" && runCatching {
            UUID.fromString(it.nameWithoutExtension).toString() == it.nameWithoutExtension
        }.getOrDefault(false)
    }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun digest(file: File) =
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun acknowledgedEventId(file: File): String? = runCatching {
        val ack = JSONObject(File(file.parentFile, file.nameWithoutExtension + ".ack").readText())
        val id = ack.getString("event_id")
        require(ack.getString("sha256") == digest(file) && AccountEventRoute.checked(id) == id)
        if (JSONObject(file.readText()).getString("state") == "timed_out") {
            require(ack.getString("recipient") == "119-mock" && ack.getString("status") == "acknowledged")
            require(
                ack.getString("receipt").startsWith("MOCK-") && ack.getString("receipt").length > 5
            )
        }
        id
    }.getOrNull()

    private fun acknowledged(file: File) = acknowledgedEventId(file) != null
    private fun restore(file: File): Boolean = runCatching {
        require(file.length() in 1..262144)
        val record = JSONObject(file.readText())
        val previous = EmergencyDemoRecord.validate(record, file.nameWithoutExtension)
        val raw = record.getJSONObject("payload")
        val restoredEvents =
            JSONArray(raw.getJSONObject("timeline").getJSONArray("events").toString())
        val restoredPayload = if (previous == "interrupted") null else JSONObject(raw.toString())
        val restoredId = if (previous in setOf(
                "responded",
                "cancelled",
                "timed_out"
            )
        ) acknowledgedEventId(file).orEmpty() else ""
        key = file.nameWithoutExtension
        origin = 0
        events = restoredEvents
        eventId = restoredId
        state = previous
        finalPayload = restoredPayload
        receipt = if (previous == "timed_out" && restoredId.isNotEmpty()) JSONObject(
            File(
                file.parentFile,
                file.nameWithoutExtension + ".ack"
            ).readText()
        ).getString("receipt") else ""
        true
    }.getOrDefault(false)

    private fun interrupt() {
        state = "interrupted"
        timer.removeCallbacks(tick)
        if (origin > 0) persist((SystemClock.elapsedRealtime() - origin) / 1000.0)
        render()
    }

    private fun begin() {
        if (!foreground || ux?.restricted == true) {
            saved.text = "정차 상태 확인 후 시작할 수 있습니다"
            return
        }
        key = UUID.randomUUID().toString()
        events = JSONArray().put(JSONObject().put("t", 0).put("kind", "unresponsive_onset"))
        origin = SystemClock.elapsedRealtime()
        state = "waiting"
        eventId = ""
        receipt = ""
        finalPayload = null
        persist(0.0)
        render()
        timer.postDelayed(tick, 500)
    }

    private fun observe(kind: String) {
        if (state != "waiting") return
        val at = (SystemClock.elapsedRealtime() - origin) / 1000.0
        val last = events.getJSONObject(events.length() - 1).getDouble("t")
        if (at <= last) return
        // 마감 전에 스케줄러나 백그라운드가 멈춘 시간은 무응답으로 보지 않는다
        if (kotlin.math.min(at, 30.0) - last > 2) {
            interrupt()
            return
        }
        events.put(JSONObject().put("t", at).put("kind", kind))
        state = when {
            at > 30 || (at >= 30 && kind == "heartbeat") -> "timed_out"
            kind == "response" -> "responded"
            kind == "cancel" -> "cancelled"
            else -> "waiting"
        }
        persist(at)
        if (state != "waiting") {
            timer.removeCallbacks(tick)
            finalPayload = payload(at)
            render()
            save()
        } else countdown.text = "${kotlin.math.ceil(30 - at).toInt()}초"
    }

    private fun save() {
        val pending = finalPayload ?: return
        if (saving || eventId.isNotEmpty() || !foreground || ux?.restricted == true) return
        saving = true
        saved.text = if (state == "timed_out") "119 접수 확인 중" else "기록 보관 중"
        retry?.isEnabled = false
        worker.execute {
            val result = runCatching {
                val response =
                    JSONObject(BackendClient().request("/api/demo/emergency-timeline", pending))
                val id = response.getString("id")
                require(AccountEventRoute.checked(id) == id)
                val report = response.getJSONObject("data").getJSONObject("observations")
                    .getJSONObject("emergency_timeline_analysis")
                require(report.getDouble("as_of") == pending.getDouble("as_of"))
                val expected = pending.getJSONObject("timeline")
                val actual = report.getJSONObject("timeline")
                for (field in listOf("schema", "source", "profile")) require(
                    expected.getString(
                        field
                    ) == actual.getString(field)
                )
                for (field in listOf("timeout_seconds", "heartbeat_max_gap_seconds")) require(
                    expected.getDouble(field) == actual.getDouble(field)
                )
                val left = expected.getJSONArray("events")
                val right = actual.getJSONArray("events")
                require(left.length() == right.length())
                for (i in 0 until left.length()) {
                    require(
                        left.getJSONObject(i).getDouble("t") == right.getJSONObject(i)
                            .getDouble("t")
                    )
                    require(
                        left.getJSONObject(i).getString("kind") == right.getJSONObject(i)
                            .getString("kind")
                    )
                }
                require(response.getBoolean("evidence_valid") && response.getString("source") == "synthetic")
                val acknowledgement = JSONObject().put("event_id", id)
                if (report.getString("status") == "timed_out") {
                    require(
                        response.getJSONObject("assessment").getString("recipient") == "119-mock"
                    )
                    val dispatches = response.getJSONArray("dispatches")
                    val matches =
                        (0 until dispatches.length()).map { dispatches.getJSONObject(it) }.filter {
                            it.optString("event_id") == id && it.optString("recipient") == "119-mock" && it.optString(
                                "status"
                            ) == "acknowledged"
                        }
                    require(matches.size == 1)
                    val received = matches.single().getString("receipt")
                    require(received.startsWith("MOCK-") && received.length > 5)
                    acknowledgement.put("receipt", received).put("recipient", "119-mock")
                        .put("status", "acknowledged")
                } else require(response.getJSONArray("dispatches").length() == 0)
                val file = File(filesDir, "emergency-demo/${pending.getString("request_key")}.json")
                val ack = File(file.parentFile, file.nameWithoutExtension + ".ack")
                val temp = File(file.parentFile, file.nameWithoutExtension + ".ack.tmp")
                temp.writeText(acknowledgement.put("sha256", digest(file)).toString())
                check(temp.renameTo(ack))
                response
            }
            runOnUiThread {
                saving = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.onSuccess {
                    restore(
                        File(
                            filesDir,
                            "emergency-demo/${pending.getString("request_key")}.json"
                        )
                    )
                    render()
                }
                    .onFailure {
                        saved.text = "기록은 기기에 보관됨"
                        retry?.visibility = android.view.View.VISIBLE
                        retry?.isEnabled = true
                    }
            }
        }
    }

    private fun render() {
        body.removeAllViews()
        retry = null
        body.addView(ui.textButton("‹ 설정") { finish() }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(12) }
        })
        // 두 Pleos 화면 모두에서 상태와 버튼을 붙여서 보여 준다
        // WRAP_CONTENT와 weight로 남는 높이를 채우고, 큰 시스템 글꼴도 잘리지 않게 한다
        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        body.addView(stage, LinearLayout.LayoutParams(-1, -2, 1f))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val contentWidth = (resources.configuration.screenWidthDp - 48).coerceIn(1, 760)
        stage.addView(content, LinearLayout.LayoutParams(ui.dp(contentWidth), -2))
        title = ui.text(
            when (state) {
                "waiting" -> "운전자 응답 확인"
                "responded" -> "응답 확인"
                "cancelled" -> "도움 요청 취소"
                "timed_out" -> if (receipt.isNotEmpty()) "119 접수 완료" else "응답 시간 초과"
                "interrupted" -> "응답 확인 중단"
                "record_unavailable" -> "기록 확인 필요"
                else -> "운전자 응답 확인"
            }, 36f, true
        ).apply { gravity = Gravity.CENTER }
        content.addView(title)
        val graphic = if (receipt.isNotEmpty()) "record_saved" else when (state) {
            "responded" -> "timeline_responded"
            "cancelled" -> "timeline_cancelled"
            "timed_out", "interrupted", "record_unavailable" -> "review"
            else -> "timeline_waiting"
        }
        val graphicSize = (resources.configuration.screenHeightDp / 4).coerceIn(160, 220)
        content.addView(
            DriverEventGraphic(this, graphic, ui.light),
            LinearLayout.LayoutParams(ui.dp(graphicSize), ui.dp(graphicSize)).apply {
                gravity = Gravity.CENTER
                topMargin = ui.dp(4)
                bottomMargin = ui.dp(4)
            })
        countdown = ui.text(
            if (state == "waiting") "30초" else when (state) {
                "interrupted" -> "도움 요청은 전송되지 않았습니다"
                "record_unavailable" -> "원본은 기기에 보관되어 있습니다"
                "timed_out" -> if (receipt.isNotEmpty()) "도움 요청 기록 확보 완료" else "응답이 확인되지 않았습니다"
                "responded", "cancelled" -> "요청하지 않았습니다"
                else -> "30초 동안 응답을 확인합니다"
            }, 28f
        ).apply { gravity = Gravity.CENTER }
        content.addView(countdown)
        if (state == "waiting") content.addView(ui.text("응답이 없으면 119에 도움을 요청합니다", 24f).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = ui.dp(8)
                bottomMargin = ui.dp(12)
            }
        })
        saved = ui.text("", 24f).apply { gravity = Gravity.CENTER }
        content.addView(saved)
        if (state == "waiting") {
            content.addView(ui.button("괜찮아요", true) { observe("response") })
            content.addView(ui.button("취소") { observe("cancel") })
        } else {
            if (eventId.isNotEmpty()) {
                saved.text = if (receipt.isNotEmpty()) "" else "기록 확보 완료"
                if (receipt.isNotEmpty()) saved.visibility = android.view.View.GONE
                content.addView(ui.button("보관 기록 보기", true) {
                    if (foreground && ux?.restricted != true) startActivity(
                        android.content.Intent(
                            this,
                            MainActivity::class.java
                        ).putExtra("review_event_id", eventId)
                    )
                })
            } else if (finalPayload != null) {
                saved.text = "기록은 기기에 보관됨"
                retry = ui.button("기록 보관 재시도", true) { save() }
                content.addView(retry)
            }
            val management = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val restart = ui.button(
                if (state == "ready") "응답 확인 시작" else "응답 확인 다시 시작",
                finalPayload == null
            ) { if (!saving) begin() }
            if (state in setOf("ready", "interrupted", "record_unavailable")) content.addView(
                restart
            )
            else management.addView(restart)
            val pending = records().filter {
                it.length() in 1..262144 && !acknowledged(it) && runCatching {
                    JSONObject(it.readText()).getString("state") in setOf(
                        "responded",
                        "cancelled",
                        "timed_out"
                    )
                }.getOrDefault(false)
            }
            if (pending.isNotEmpty()) {
                val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                pending.forEach { file ->
                    val date = java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.KOREA)
                        .format(java.util.Date(file.lastModified()))
                    list.addView(ui.button("$date 기록") {
                        if (!saving) {
                            if (!restore(file)) {
                                state = "record_unavailable"
                                finalPayload = null
                                eventId = ""
                            }
                            render()
                        }
                    })
                }
                ui.disclosure(management, "보관 확인 ${pending.size}건", list)
            }
            if (management.childCount > 0) ui.disclosure(content, "시연 관리", management)
        }
    }
}
