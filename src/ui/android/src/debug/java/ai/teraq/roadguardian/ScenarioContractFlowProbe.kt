package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Live authored sensor fixtures; actual Main methods and playback, not taps or camera inference. */
class ScenarioContractFlowProbe : Instrumentation() {
    private var captureHoldMs = 0L
    private var reviewOnly = false
    private var reviewId: String? = null
    private var emergencyTimeline = false
    private var stationaryOnly = false
    private var observationMedia = false
    private var observations = false
    private var weather = false
    private var help = false
    private var weatherOnly = false
    private var surroundViews = false
    private var nonAirbag = false
    private var crashOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        captureHoldMs =
            arguments?.getString("capture_hold_ms")?.toLongOrNull()?.coerceIn(0L, 3000L) ?: 0L
        surroundViews = arguments?.getString("suite") == "surround_views"
        reviewOnly = arguments?.getString("suite") == "review_only"
        nonAirbag = arguments?.getString("nonAirbag") == "true"
        emergencyTimeline = arguments?.getString("suite") == "emergency_timeline"
        stationaryOnly = arguments?.getString("suite") == "stationary_media"
        observationMedia = stationaryOnly || arguments?.getString("suite") == "observations_media"
        observations = observationMedia || arguments?.getString("suite") == "observations"
        reviewId = arguments?.getString("reviewId")
        crashOnly = arguments?.getString("suite") == "crash_action"
        weatherOnly = arguments?.getString("suite") == "weather_only"
        help = arguments?.getString("suite") == "help"
        weather = weatherOnly || help || arguments?.getString("suite") == "weather"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var main: MainActivity? = null
        var player: Activity? = null
        try {
            val client =
                BackendClient(if (crashOnly) "http://127.0.0.1:9466" else if (weather) "http://127.0.0.1:9465" else "http://127.0.0.1:9464")
            val manifest = if (help) JSONObject(
                File(
                    targetContext.filesDir,
                    "help-request-media-manifest.json"
                ).readText()
            ) else JSONObject(client.request(if (weather) "/api/dev/weather-help-fixture" else "/api/dev/scenario-contract-fixture"))
            val ids = manifest.getJSONObject("ids")
            val rows = JSONArray()
            main = startActivitySync(
                Intent(
                    targetContext,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val activity = main
            fun field(name: String) =
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

            fun method(name: String) =
                MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }

            fun await(label: String, test: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 18000
                while (SystemClock.elapsedRealtime() < deadline) {
                    var ok = false
                    runOnMainSync { ok = test() }
                    if (ok) return
                    Thread.sleep(50)
                }
                error("Timeout: $label")
            }

            fun labels(screen: Activity): List<TextView> {
                val nodes = mutableListOf<TextView>()
                fun visit(view: View) {
                    if (!view.isShown) return
                    if (view is TextView) nodes.add(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(
                        view.getChildAt(
                            i
                        )
                    )
                }
                visit(screen.window.decorView)
                return nodes
            }

            data class PixelAnchor(val bitmap: Bitmap, val left: Int, val top: Int, val ink: Int)

            fun frame(screen: Activity, name: String, vararg expected: String) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val anchors = mutableListOf<PixelAnchor>()
                    runOnMainSync {
                        for (text in expected) labels(screen).firstOrNull { it.text.toString() == text && it.layout?.text?.toString() == text && !it.isLayoutRequested && it.width > 0 && it.height > 0 }
                            ?.let { view ->
                                val at = IntArray(2)
                                view.getLocationOnScreen(at)
                                val bitmap = Bitmap.createBitmap(
                                    view.width,
                                    view.height,
                                    Bitmap.Config.ARGB_8888
                                ).also { view.draw(Canvas(it)) }
                                anchors.add(
                                    PixelAnchor(
                                        bitmap,
                                        at[0],
                                        at[1],
                                        view.currentTextColor
                                    )
                                )
                            }
                    }
                    if (anchors.size != expected.size) {
                        anchors.forEach { it.bitmap.recycle() }
                        Thread.sleep(60)
                        continue
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                    val presented = try {
                        anchors.all { (mask, left, top, ink) ->
                            var pixels = 0
                            var matched = 0
                            for (y in 0 until mask.height) for (x in 0 until mask.width) {
                                val value = mask.getPixel(x, y)
                                if (Color.alpha(value) < 245 || kotlin.math.abs(
                                        Color.red(value) - Color.red(
                                            ink
                                        )
                                    ) > 24 || kotlin.math.abs(Color.green(value) - Color.green(ink)) > 24 || kotlin.math.abs(
                                        Color.blue(value) - Color.blue(ink)
                                    ) > 24
                                ) continue
                                pixels++
                                if (left + x !in 0 until screenshot.width || top + y !in 0 until screenshot.height) continue
                                val actual = screenshot.getPixel(left + x, top + y)
                                if (kotlin.math.abs(Color.red(value) - Color.red(actual)) < 12 && kotlin.math.abs(
                                        Color.green(value) - Color.green(actual)
                                    ) < 12 && kotlin.math.abs(Color.blue(value) - Color.blue(actual)) < 12
                                ) matched++
                            }
                            pixels > 30 && matched >= pixels * .97
                        }
                    } finally {
                        anchors.forEach { it.bitmap.recycle() }
                    }
                    if (presented && SystemClock.elapsedRealtime() <= deadline) {
                        File(targetContext.filesDir, name).outputStream()
                            .use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        screenshot.recycle()
                        if (captureHoldMs > 0) Thread.sleep(captureHoldMs)
                        return
                    }
                    screenshot.recycle()
                    Thread.sleep(60)
                }
                error("Native expected pixels absent: $name / ${expected.toList()}")
            }

            var crashPrepared = false
            var crashDraft: JSONObject? = null
            fun partialStatus(event: JSONObject): String {
                val meta = event.getJSONObject("surround_views")
                val missing = meta.getJSONArray("missing_directions")
                val names = mapOf("front" to "전방", "rear" to "후방", "left" to "왼쪽", "right" to "오른쪽")
                return "합성 사진 ${
                    meta.getJSONArray("views").length()
                }개 보관 · " + (0 until missing.length()).joinToString("·") {
                    names.getValue(
                        missing.getString(
                            it
                        )
                    )
                } + " 사진 없음"
            }

            fun readyDetail(case: String, title: String, phase: String) {
                val expected = when (case) {
                    "timeline_waiting" -> arrayOf(title, "도움 요청 대기", "상세 기록 펼치기")
                    "timeline_responded" -> arrayOf(title, "도움 요청 안 함", "상세 기록 펼치기")
                    "timeline_cancelled" -> arrayOf(title, "요청을 보내지 않았습니다", "상세 기록 펼치기")
                    "violation", "reckless" -> arrayOf(title, "기록 보관 · 확인 필요", "상세 기록 펼치기")
                    "wanted" -> arrayOf(title, "합성 대조 결과 · 실제 신고 없음", "상세 기록 펼치기")
                    "rain" -> arrayOf(title, "속도를 줄이고 거리를 확보하세요", "상세 기록 펼치기")
                    "car_wash" -> arrayOf(title, "기록을 보관했습니다", "상세 기록 펼치기")
                    "washer" -> arrayOf(title, "측정 기록 보관 완료", "상세 기록 펼치기")
                    "emergency", "help_119", "help_112" -> arrayOf(title, "접수 완료", "상세 기록 펼치기")
                    "crash" -> if (crashPrepared) arrayOf(
                        title,
                        "보험 연결 준비 완료",
                        "외부 전송 없음"
                    ) else arrayOf(title, "보험사 연결을 준비할까요?", "연결 준비")

                    "pothole" -> if (surroundViews) arrayOf(
                        title,
                        "합성 사진 4개 보관",
                        "방향별 합성 사진 · 실제 촬영 아님",
                        "전방",
                        "후방",
                        "왼쪽",
                        "오른쪽"
                    ) else arrayOf(title, "센서·합성 이미지 보관 완료", "전방", "후방", "왼쪽", "오른쪽")

                    "partial" -> arrayOf(
                        title,
                        partialStatus(JSONObject(field("opened").get(activity).toString())),
                        "전방",
                        "후방",
                        "왼쪽",
                        "오른쪽"
                    )

                    "corrupt" -> arrayOf(title, "센서 기록 보관 · 사진 확인 실패")
                    "review" -> arrayOf(title, "원문 보관 · 판정 보류")
                    else -> arrayOf(title, "측정 기록 보관 완료", "상세 기록 펼치기")
                }
                val presentedExpected =
                    expected + if (surroundViews) arrayOf("상세 기록 펼치기") else emptyArray<String>()
                await("$case ready $phase") { presentedExpected.all { it in labels(activity).map { node -> node.text.toString() } } }
                frame(activity, "scenario-contract-$case-$phase-20261102.png", *presentedExpected)
            }

            fun stopPoll() {
                val handler = field("timer").get(activity) as Handler
                for (name in listOf(
                    "feedPoll",
                    "drivingWatch"
                )) handler.removeCallbacks(field(name).get(activity) as Runnable)
            }
            await("idle/OEM allowance") {
                !field("busy").getBoolean(activity) && !field("comparisonLoading").getBoolean(
                    activity
                ) && (field("uxMonitor").get(activity) as? VehicleUxMonitor)?.restricted == false
            }
            runOnMainSync {
                stopPoll()
                field("client").set(activity, client)
                (field("drivingState").get(activity) as DrivingState).apply {
                    useSimulation(true)
                    setSimulatedMoving(false)
                }
                field("selected").set(activity, "기록")
                field("light").setBoolean(activity, true)
            }
            val openVideo = MainActivity::class.java.getDeclaredMethod(
                "openComparisonVideo",
                String::class.java,
                String::class.java
            ).apply { isAccessible = true }

            fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            for (case in (if (surroundViews) listOf(
                "pothole",
                "partial",
                "corrupt"
            ) else if (reviewOnly) emptyList() else if (emergencyTimeline) listOf(
                "emergency",
                "timeline_waiting",
                "timeline_responded",
                "timeline_cancelled"
            ) else if (stationaryOnly) listOf("violation") else if (observations) listOf(
                "violation",
                "reckless",
                "wanted"
            ) else if (crashOnly) listOf("crash") else if (help) listOf(
                "help_119",
                "help_112"
            ) else if (weatherOnly) listOf(
                "rain",
                "washer",
                "car_wash"
            ) else if (weather) listOf(
                "rain",
                "washer",
                "emergency",
                "help_119",
                "help_112"
            ) else listOf(
                "pothole",
                "speed_bump",
                "crash"
            )) + if (reviewId.isNullOrBlank()) emptyList() else listOf("review")) {
                val id = if (case == "review") checkNotNull(reviewId) else ids.getString(case)
                runOnMainSync {
                    stopPoll()
                    field("opened").set(activity, null)
                    field("detailReturnId").set(activity, id)
                    method("refreshReturnedDetail").invoke(activity)
                }
                await("fresh $case detail") {
                    field("detailReturnId").get(activity) == null && (field(
                        "opened"
                    ).get(activity) as? JSONObject)?.optString("id") == id
                }
                lateinit var event: JSONObject
                val title = when (case) {
                    "timeline_waiting" -> "운전자 응답 확인 중"
                    "timeline_responded" -> "운전자 응답 확인"
                    "timeline_cancelled" -> "도움 요청 취소"
                    "violation" -> "주정차 위반 후보"
                    "reckless" -> "급격한 주행 변화"
                    "wanted" -> "시험 목록 번호 일치"
                    "pothole", "partial", "corrupt" -> "포트홀 감지"
                    "crash" -> if (nonAirbag) "충돌 후보 확인" else "사고 감지"
                    "review" -> "입력 확인 필요"
                    "rain" -> "전방 시야 저하"
                    "car_wash" -> "강우 판정 보류"
                    "emergency" -> "응답 시간 초과"
                    "help_119" -> "119 도움 요청"
                    "help_112" -> "112 도움 요청"
                    else -> "관측 자료 확인"
                }
                runOnMainSync {
                    event = JSONObject((field("opened").get(activity) as JSONObject).toString())
                    check(event.getString("id") == id)
                    if (case != "review") {
                        check(
                            event.getJSONObject("assessment").getString("kind") == when (case) {
                                "timeline_waiting", "timeline_responded", "timeline_cancelled", "speed_bump", "washer", "car_wash" -> "observation"
                                "help_119", "help_112" -> "manual_help"
                                "partial", "corrupt" -> "pothole"
                                else -> case
                            }
                        )
                        check(event.opt("evidence_valid") == true)
                    } else check(event.optString("status") == "input_review_required")
                }
                if (case == "crash" && nonAirbag) {
                    check(
                        event.getJSONObject("data").getJSONObject("observations")
                            .opt("airbag") == false
                    )
                    check(
                        event.getJSONObject("assessment").getJSONObject("collision_assessment")
                            .getString("status") == "authored_collision_candidate"
                    )
                }
                readyDetail(case, title, "detail")
                if (surroundViews) {
                    runOnMainSync {
                        check(labels(activity).none { it.text.toString() == "도로 근거 정리" })
                        check(labels(activity).single { it.text.toString() == "상세 기록 펼치기" }
                            .performClick())
                    }
                    await("$case expanded details") { labels(activity).any { it.text.toString() == "도로 근거 정리" && it.height > 0 && !it.isLayoutRequested } }
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "도로 근거 정리" }.let {
                            it.requestRectangleOnScreen(
                                android.graphics.Rect(
                                    0,
                                    0,
                                    it.width,
                                    it.height
                                ), true
                            )
                        }
                    }
                    frame(activity, "scenario-contract-$case-expanded-20261102.png", "도로 근거 정리")
                    runOnMainSync {
                        check(labels(activity).single { it.text.toString() == "상세 기록 접기" }
                            .performClick())
                    }
                    await("$case details hidden") { labels(activity).none { it.text.toString() == "도로 근거 정리" } }
                    readyDetail(case, title, "collapsed")
                    runOnMainSync { check((field("opened").get(activity) as JSONObject).getString("id") == id) }
                }
                if (surroundViews && case in setOf("partial", "corrupt")) {
                    runOnMainSync {
                        check(labels(activity).none {
                            it.text.toString() in setOf(
                                "합성 사진 4개 보관",
                                "센서·합성 이미지 보관 완료"
                            )
                        })
                        if (case == "corrupt") check(labels(activity).none {
                            it.text.toString() in setOf(
                                "전방",
                                "후방",
                                "왼쪽",
                                "오른쪽"
                            )
                        })
                    }
                    if (case == "partial") {
                        val partialZip =
                            File(targetContext.filesDir, "surround-partial.zip").apply {
                                writeBytes(client.evidencePackage(id))
                            }
                        EvidencePackageVerifier.verify(partialZip, id)
                    } else check(runCatching { client.evidencePackage(id) }.isFailure)
                    rows.put(
                        JSONObject().put("case", case).put("event_id", id)
                            .put("details_expand_collapse", true).put("false_success_absent", true)
                            .put("partial_zip_verified", case == "partial")
                            .put("corrupt_export_refused", case == "corrupt")
                    )
                    continue
                }
                if (case == "car_wash") {
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "상세 기록 펼치기" }.performClick()
                    }
                    waitForIdleSync()
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "신호만으로 강우를 확정할 수 없어 판정을 보류했습니다." }
                            .let {
                                it.requestRectangleOnScreen(
                                    android.graphics.Rect(
                                        0,
                                        0,
                                        it.width,
                                        it.height
                                    ), true
                                )
                            }
                    }
                    frame(
                        activity,
                        "scenario-contract-car_wash-expanded-20261102.png",
                        "합성 맥락: 세차 · 영상 인식 결과 아님",
                        "신호만으로 강우를 확정할 수 없어 판정을 보류했습니다."
                    )
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "상세 기록 접기" }.performClick()
                    }
                }
                if (case == "review") {
                    runOnMainSync {
                        val shown = labels(activity).map { it.text.toString() }
                        check(shown.none {
                            it in setOf(
                                "사고 감지",
                                "저전압 확인",
                                "보험사 연결을 준비할까요?",
                                "센터 예약을 준비할까요?",
                                "연결 준비",
                                "예약 준비"
                            )
                        })
                    }
                    rows.put(
                        JSONObject().put("case", case).put("event_id", id).put("title", title)
                            .put("success_actions_absent", true)
                    )
                    continue
                }
                if (observations) {
                    check(event.getJSONObject("assessment").getString("kind") == case)
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "상세 기록 펼치기" }.performClick()
                    }
                    val heading = when (case) {
                        "violation" -> "주변 차량 근거"
                        "reckless" -> "주행 변화 근거"
                        else -> "목록 확인 근거"
                    }
                    await("$case details reachable") { labels(activity).any { it.text.toString() == heading } }
                    if (stationaryOnly) {
                        runOnMainSync {
                            labels(activity).single { it.text.toString() == "연속 정지 · 합성 영상 계산" }
                                .let {
                                    it.requestRectangleOnScreen(
                                        android.graphics.Rect(
                                            0,
                                            0,
                                            it.width,
                                            it.height
                                        ), true
                                    )
                                }
                        }
                        frame(
                            activity,
                            "stationary-expanded-20261102.png",
                            "연속 정지 · 합성 영상 계산",
                            "129초"
                        )
                    }
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "상세 기록 접기" }.performClick()
                    }
                    readyDetail(case, title, "return")
                    if (!observationMedia) {
                        rows.put(
                            JSONObject().put("case", case).put("event_id", id).put("title", title)
                                .put("native_summary_verified", true)
                                .put("details_expand_collapse", true).put("media_verified", false)
                        )
                        continue
                    }
                }
                if (crashOnly && nonAirbag) {
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "상세 기록 펼치기" }.performClick()
                    }
                    waitForIdleSync()
                    await("collision detail layout") { labels(activity).any { it.text.toString() == "에어백 미전개 입력에서도 충돌 후보 기록을 보관합니다." && it.height > 0 && !it.isLayoutRequested } }
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "에어백 미전개 입력에서도 충돌 후보 기록을 보관합니다." }
                            .let {
                                it.requestRectangleOnScreen(
                                    android.graphics.Rect(
                                        0,
                                        0,
                                        it.width,
                                        it.height
                                    ), true
                                )
                            }
                    }
                    frame(
                        activity,
                        "nonairbag-collision-expanded.png",
                        "합성 충돌 관측 · 영상 인식 결과 아님",
                        "에어백 미전개 입력에서도 충돌 후보 기록을 보관합니다."
                    )
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "상세 기록 접기" }.performClick()
                    }
                }
                if (crashOnly) {
                    val initialDispatches = event.getJSONArray("dispatches").length()
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "나중에" }.performClick()
                    }
                    await("deferred crash") { labels(activity).any { it.text.toString() == "나중에 확인" } }
                    check(
                        DemoActionDraftStore.read(targetContext, event)
                            ?.optString("state") == "deferred"
                    )
                    runOnMainSync {
                        field("opened").set(activity, null)
                        field("detailReturnId").set(activity, id)
                        method("refreshReturnedDetail").invoke(activity)
                    }
                    await("deferred reopen") {
                        field("detailReturnId").get(activity) == null && labels(
                            activity
                        ).any { it.text.toString() == "나중에 확인" }
                    }
                    frame(activity, "crash-action-deferred.png", "나중에 확인", "연결 준비")
                    runOnMainSync {
                        labels(activity).single { it.text.toString() == "연결 준비" }.performClick()
                    }
                    await("prepared crash") { labels(activity).any { it.text.toString() == "보험 연결 준비 완료" } }
                    crashDraft = checkNotNull(DemoActionDraftStore.read(targetContext, event))
                    check(
                        crashDraft!!.getString("event_id") == id && crashDraft!!.getString("sensor_sha256") == event.getString(
                            "hash"
                        )
                    )
                    check(
                        crashDraft!!.getString("fingerprint") == DemoActionDraftStore.fingerprint(
                            event
                        )
                    )
                    check(crashDraft!!.getString("state") == "prepared" && crashDraft!!.opt("external_submission") == false)
                    File(
                        targetContext.filesDir,
                        "crash-action-draft.json"
                    ).writeText(crashDraft!!.toString(2))
                    crashPrepared = true
                    frame(activity, "crash-action-prepared.png", "보험 연결 준비 완료", "외부 전송 없음")
                    val after = JSONObject(client.request("/api/events/$id"))
                    check(
                        after.getString("hash") == event.getString("hash") && after.getJSONArray("dispatches")
                            .length() == initialDispatches
                    )
                    check(
                        after.getJSONObject("insurance_case")
                            .toString() == event.getJSONObject("insurance_case").toString()
                    )
                }
                val contractRows = manifest.getJSONArray("rows")
                val contract =
                    (0 until contractRows.length()).map { contractRows.getJSONObject(it) }
                        .single { it.getString("scenario") == case }
                check(
                    contract.getString("event_id") == id && contract.getString("input_sha256") == event.getString(
                        "hash"
                    )
                )
                // VideoActivity uses its production default endpoint; device reverse must point
                // that endpoint at this same fixture. Never substitute a different clip.
                val playbackEvent = JSONObject(BackendClient().request("/api/events/$id"))
                check(playbackEvent.getString("hash") == event.getString("hash"))
                check(
                    playbackEvent.getJSONObject("video").getString("sha256") == event.getJSONObject(
                        "video"
                    ).getString("sha256")
                )
                val zip = File(
                    targetContext.filesDir,
                    "scenario-contract-$case-20261102.zip"
                ).apply { writeBytes(client.evidencePackage(id)) }
                EvidencePackageVerifier.verify(zip, id)
                ZipFile(zip).use { archive ->
                    fun bytes(name: String) =
                        archive.getInputStream(checkNotNull(archive.getEntry(name))).readBytes()

                    val report = JSONObject(bytes("event-report.json").toString(Charsets.UTF_8))
                    check(sha(bytes("sensor-original.json")) == event.getString("hash"))
                    check(
                        sha(bytes("test-pattern-video.mp4")) == event.getJSONObject("video")
                            .getString("sha256")
                    )
                    val photo = report.getJSONObject("video").getJSONObject("input_photo")
                    val original = event.getJSONObject("video").getJSONObject("input_photo")
                    val path = photo.getString("path")
                    check(path in setOf("input-photo.png", "input-photo.jpg"))
                    check(
                        photo.getString("event_id") == id && photo.getString("sensor_sha256") == event.getString(
                            "hash"
                        )
                    )
                    check(
                        sha(bytes(path)) == original.getString("sha256") && photo.getString("sha256") == original.getString(
                            "sha256"
                        )
                    )
                    check(photo.getLong("bytes") == bytes(path).size.toLong())
                    if (case == "pothole" && surroundViews) {
                        val expected = SyntheticSurroundViews.validate(
                            event.getJSONObject("surround_views"),
                            id,
                            event.getString("hash"),
                            true
                        )
                        val archived = SyntheticSurroundViews.validate(
                            report.getJSONObject("surround_views"),
                            id,
                            event.getString("hash"),
                            true
                        )
                        check(SyntheticSurroundViews.same(expected, archived))
                        archived.forEach { row ->
                            check(
                                sha(bytes(row.getString("path"))) == row.getString(
                                    "sha256"
                                )
                            )
                        }
                    } else if (case == "pothole") {
                        val atlas = report.getJSONObject("surround")
                        check(
                            atlas.getString("event_id") == id && atlas.getString("sensor_sha256") == event.getString(
                                "hash"
                            )
                        )
                        check(
                            sha(bytes(atlas.getString("path"))) == contract.getJSONObject("surround")
                                .getString("sha256")
                        )
                        check(
                            atlas.getJSONArray("directions").toString() == JSONArray(
                                listOf(
                                    "front",
                                    "rear",
                                    "left",
                                    "right"
                                )
                            ).toString()
                        )
                    }
                }
                val monitor = addMonitor(VideoActivity::class.java.name, null, false)
                try {
                    runOnMainSync { openVideo.invoke(activity, id, id) }
                    player = checkNotNull(waitForMonitorWithTimeout(monitor, 10000))
                } finally {
                    removeMonitor(monitor)
                }
                val playing = checkNotNull(player)
                var position = 0
                await("$case actual playback") {
                    val video = VideoActivity::class.java.getDeclaredField("video")
                        .apply { isAccessible = true }.get(playing) as VideoView
                    position = video.currentPosition
                    video.isPlaying && video.visibility == View.VISIBLE && position > 1200
                }
                frame(playing, "scenario-contract-$case-playing-20261102.png", "재생 중 · 입력 파일 확인됨")
                runOnMainSync { playing.finish() }
                player = null
                await("$case same-event return") {
                    field("detailReturnId").get(activity) == null && (field(
                        "opened"
                    ).get(activity) as? JSONObject)?.optString("id") == id
                }
                runOnMainSync {
                    stopPoll()
                    check(title in labels(activity).map { it.text.toString() })
                }
                readyDetail(case, title, "return")
                rows.put(
                    JSONObject().put("case", case).put("event_id", id).put("title", title)
                        .put("position_ms", position)
                        .put("sensor_sha256", event.getString("hash"))
                        .put("clip_sha256", event.getJSONObject("video").getString("sha256"))
                        .put("media_verified", true)
                        .put("details_expand_collapse", observations || surroundViews)
                        .put("same_event_return", true).put("zip_verified", true).put(
                            "local_action_prepared",
                            crashOnly && crashDraft?.optString("state") == "prepared"
                        ).put("external_submission", false)
                        .put("ready_action_pixels_verified", case == "crash").put(
                            "photo_sha256",
                            event.getJSONObject("video").getJSONObject("input_photo")
                                .getString("sha256")
                        ).put("developer_detail_route", false)
                        .put("four_direction_labels_verified", case == "pothole")
                )
            }
            if (crashOnly) {
                val unavailableId = ids.getString("crash_unavailable")
                runOnMainSync {
                    field("opened").set(activity, null)
                    field("detailReturnId").set(activity, unavailableId)
                    method("refreshReturnedDetail").invoke(activity)
                }
                await("insurance unavailable") {
                    field("detailReturnId").get(activity) == null && (field(
                        "opened"
                    ).get(activity) as? JSONObject)?.optString("id") == unavailableId
                }
                runOnMainSync {
                    val unavailable = field("opened").get(activity) as JSONObject
                    check(DemoActionDraftStore.eligibleAction(unavailable) == null)
                    check(labels(activity).none {
                        it.text.toString() in setOf(
                            "연결 준비",
                            "보험 연결 준비 완료"
                        )
                    })
                }
                frame(activity, "crash-action-unavailable.png", "차량·보험 확인 필요")
                rows.put(
                    JSONObject().put("case", "crash_unavailable").put("event_id", unavailableId)
                        .put("prepare_actions_absent", true)
                )
            }
            File(
                targetContext.filesDir,
                if (surroundViews) "surround-views-flow.json" else if (emergencyTimeline) "emergency-timeline-flow-20261102.json" else if (stationaryOnly) "stationary-media-flow-20261102.json" else if (observationMedia) "observation-media-flow-20261102.json" else if (observations) "observation-flow-20261102.json" else if (crashOnly) "crash-action-flow-20261102.json" else if (help) "help-media-flow-20261102.json" else if (weatherOnly) "weather-only-flow-20261102.json" else if (weather) "weather-help-flow-20261102.json" else "scenario-contract-flow-20261102.json"
            ).writeText(
                JSONObject().put("pass", true).put("cases", rows)
                    .put(
                        "scope",
                        if (reviewOnly) "Malformed input review only: actual Main GET, visible withholding summary and no preparation actions. No media playback, ZIP or physical vehicle verification in this run." else if (surroundViews) "Four separate authored directional photos, not real cameras. Actual Main detail expand/collapse for normal, missing and corrupt records; normal playback/return/ZIP, partial ZIP and corrupt export refusal. Programmatic actions; no camera inference, picker write, human readability or physical driving." else if (emergencyTimeline) "Authored nonvisual timeline replay; actual Main outcomes, illustrative video playback, same-event ZIP and return. No live timer, camera/medical detection, direct touch or physical vehicle testing." else if (stationaryOnly) "Controlled fixed-marker stationary video: full 130-second source hash in playback and ZIP, measured sampled dwell in collapsed details. Authored zone, no general road recognition or legal verdict. Programmatic emulator actions." else if (observationMedia) "Same-event photo/video/ZIP and actual emulator playback with collapsed return. Violation/wanted clips repeat static photos; reckless uses controlled color-marker tracking, not general road-scene inference. Programmatic actions, no direct taps or physical vehicle testing." else if (observations) "Authored lower-priority observation inputs and optional malformed-input control. Actual Main fresh GET and native summary pixels only; no media verification, camera inference, direct touch, external submission or physical vehicle testing." else if (crashOnly) "One authored crash event: actual Main performClick defer/reopen/prepare, persisted local draft, same-event playback and ZIP. One insurance-unavailable refusal control, without media playback. No external submission, camera inference, direct touch or real vehicle readability." else if (weather) "Authored weather/help records listed in cases; Main fresh GET, native pixels, actual playback, same-event return and ZIP sensor/media binding. Signal timelines are illustrative only; policy does not analyze sequence. Not user request selection/cancel, camera inference, real agencies, direct taps or real vehicle readability." else "Authored nonvisual sensor input and synthetic photo/video; Main fresh GET/method routes, native text pixels, actual playback and same-event return, sensor/photo/video ZIP checks. Pothole uses one authored atlas with four regions, not independent cameras. Speed bump remains observation with collapsed detail. No camera inference, direct taps, document picker write, human readability or physical driving."
                    ).toString(2)
            )
            result.putString(
                "stream",
                if (reviewOnly) "PASS: malformed input withholding summary pixels and absent success actions; no media/ZIP verification in this run.\n" else if (observations && !observationMedia) "PASS: ${rows.length()} authored observation/review rows, fresh Main GET and native summary pixels; no media or camera inference claim.\n" else "PASS: ${rows.length()} scenario rows fresh Main GET, native pixels, same-event playback/return and sensor/photo/video ZIP. Optional review status checked only when supplied. No direct taps or camera inference.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            runCatching {
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(
                        targetContext.filesDir,
                        "scenario-contract-failure-20261102.png"
                    ).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                player?.finish()
                main?.finish()
            }
        }
    }
}
