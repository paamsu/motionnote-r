package ai.teraq.roadguardian

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.LinearLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** 화면 수명에 묶인 개인 관측. 포그라운드, 기어, OEM UX 제한은 화면을 띄운 쪽이 맡는다 */
class SdkObservationsPanel(
    context: Context,
    private val ui: PhoneAccountUi,
    private val eventId: String,
    private val syntheticCase: Boolean,
    private val allowed: () -> Boolean,
    private val snapshot: (() -> JSONObject)? = null
) : LinearLayout(context) {
    private var worker: java.util.concurrent.ExecutorService? = null
    private val timer = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var lease: AccountDisplayLease? = null
    private var session = ""
    private var records = emptyList<SdkObservationRecord>()
    private var writable = false
    private var expanded = false
    private var pending: JSONObject? = null
    private var notice = ""
    private var uncertain = false
    private var cleared = false
    private val automotive =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private val emulator = android.os.Build.HARDWARE in setOf(
        "ranchu",
        "goldfish"
    ) || android.os.Build.FINGERPRINT.startsWith("generic")
    private val scale =
        if (automotive) (resources.configuration.screenWidthDp / 1100f).coerceIn(1f, 1.65f) else 1f
    private val watch = object : Runnable {
        override fun run() {
            val current = lease
            if (!allowed() || (current != null && (!current.valid(
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime()
                ) ||
                        runCatching { AuthenticatedEventsClient(context).sessionFingerprint() != session }.getOrDefault(
                            true
                        )))
            ) clearPrivate()
            if (isAttachedToWindow) timer.postDelayed(this, 500)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(0, ui.dp(20), 0, ui.dp(12))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        worker = Executors.newSingleThreadExecutor()
        load()
        timer.post(watch)
    }

    override fun onDetachedFromWindow() {
        generation++
        timer.removeCallbacksAndMessages(null)
        worker?.shutdownNow()
        worker = null
        lease = null
        session = ""
        records = emptyList()
        pending = null
        removeAllViews()
        super.onDetachedFromWindow()
    }

    private fun text(value: String, size: Float = 18f, bold: Boolean = false) {
        addView(ui.text(value, size * scale, bold))
    }

    private fun button(value: String, primary: Boolean = false, action: () -> Unit) {
        addView(ui.button(value, primary, action).apply { textSize = 18f * scale })
    }

    private fun heading(subtitle: String) {
        removeAllViews()
        text("이 사건의 추가 관측", 23f, true)
        text(subtitle, 17f)
    }

    private fun clearPrivate() {
        if (cleared) return
        cleared = true
        generation++
        lease = null
        session = ""
        records = emptyList()
        pending = null
        writable = false
        heading("화면 제한 또는 계정 변경으로 관측을 화면에서 지웠습니다.")
        if (allowed()) button("다시 조회", true) { load() }
    }

    private fun message(failure: Throwable, write: Boolean = false): String {
        val issue = failure as? AccountAccessException
        return when (issue?.reason) {
            AccountAccessException.Reason.FORBIDDEN -> "현재 계정은 추가 관측을 저장할 수 없습니다."
            AccountAccessException.Reason.CONFLICT -> "요청 내용이 겹치거나 이 사건의 보관 한도에 도달했습니다. 목록을 다시 확인해 주세요."
            AccountAccessException.Reason.NOT_FOUND -> "이 기록의 추가 관측을 조회할 수 없습니다. 서비스 설정과 차량 권한을 확인해 주세요."
            AccountAccessException.Reason.SIGN_IN, AccountAccessException.Reason.EXPIRED, AccountAccessException.Reason.CHANGED -> "계정 연결이 바뀌었거나 만료됐습니다. 사건을 다시 조회해 주세요."
            else -> if (write) "보관 결과를 확인하지 못했습니다. 같은 자료로 다시 확인하거나 목록을 조회해 주세요." else "추가 관측을 불러오지 못했습니다. 연결을 확인해 주세요."
        }
    }

    private fun load(savedMessage: String = "") {
        if (!allowed()) {
            clearPrivate()
            return
        }
        cleared = false
        uncertain = false
        val attempt = ++generation
        lease = null
        pending = null
        notice = savedMessage
        heading("본인 계정에 보관한 관측을 확인하고 있습니다.")
        worker?.execute {
            val result = runCatching {
                val client = AuthenticatedEventsClient(context)
                val capabilities = client.capabilitiesForDisplay()
                if (!capabilities.value.observationsRead) return@runCatching Triple(
                    capabilities,
                    null,
                    false
                )
                val observed = client.sdkObservationsForDisplay(eventId)
                if (observed.sessionFingerprint != capabilities.sessionFingerprint) throw AccountAccessException(
                    AccountAccessException.Reason.CHANGED
                )
                Triple(
                    capabilities,
                    observed,
                    capabilities.value.observationsWrite && client.canObserve()
                )
            }
            post {
                if (!isAttachedToWindow || attempt != generation || !allowed()) return@post
                result.fold(onSuccess = { bundle ->
                    val received = bundle.second
                    if (received == null) {
                        heading("이 서비스는 추가 관측을 제공하지 않습니다. 기존 사건 기록은 계속 확인할 수 있습니다.")
                        return@fold
                    }
                    if (!received.lease.valid(
                            System.currentTimeMillis(),
                            SystemClock.elapsedRealtime()
                        )
                    ) {
                        clearPrivate()
                        return@fold
                    }
                    lease = received.lease
                    session = received.sessionFingerprint
                    records = received.value
                    writable =
                        bundle.third && snapshot != null && automotive && (!syntheticCase || emulator)
                    render()
                }, onFailure = {
                    heading(message(it))
                    button("다시 조회", true) { load() }
                })
            }
        }
    }

    private fun date(value: Double) =
        SimpleDateFormat("M월 d일 HH:mm:ss", Locale.KOREAN).format(Date((value * 1000).toLong()))

    private fun gear(value: String?) = when (value) {
        "GEAR_PARK" -> "P"
        "GEAR_REVERSE" -> "R"
        "GEAR_NEUTRAL" -> "N"
        "GEAR_DRIVE" -> "D"
        else -> "미확인"
    }

    private fun summary(sample: SdkObservationRecord.Sample) {
        if (automotive && resources.configuration.screenWidthDp >= 760 && resources.configuration.fontScale < 1.3f) {
            addView(
                ObservationSignalGraphic(context, sample, ui.light),
                LayoutParams(-1, ui.dp((160 * scale).toInt()))
            )
            text(
                "기록 당시 수신 경과 · 기어 ${sample.gearAge?.let { "${it}ms" } ?: "미확인"} / 속도 ${sample.speedAge?.let { "${it}ms" } ?: "미확인"}",
                15f
            )
            return
        }
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        val speed = sample.speed?.let { String.format(Locale.KOREAN, "%.1f km/h", it) } ?: "미확인"
        row.addView(ui.text("기어 ${gear(sample.gear)}", 25f * scale, true), LayoutParams(0, -2, 1f))
        row.addView(ui.text("속도 $speed", 25f * scale, true), LayoutParams(0, -2, 1f))
        if (resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 520) row.orientation =
            VERTICAL
        if (row.orientation == VERTICAL) for (i in 0 until row.childCount) row.getChildAt(i).layoutParams =
            LayoutParams(-1, -2)
        addView(row)
        text(
            "기록 당시 수신 경과 · 기어 ${sample.gearAge?.let { "${it}ms" } ?: "미확인"} / 속도 ${sample.speedAge?.let { "${it}ms" } ?: "미확인"}",
            15f
        )
    }

    private fun render() {
        heading("본인 계정에만 보관 · 센서 원본과 별도")
        if (notice.isNotEmpty()) addView(ui.notice(notice))
        text("보관한 관측 ${records.size}건 / 최대 64건", 18f, true)
        if (writable && records.size < 64) button("현재 차량 관측 확인", true) { preview() }
        if (records.isEmpty()) text("아직 추가 관측이 없습니다. 사고 판정이나 정비 완료를 뜻하는 자료는 아닙니다.")
        val visible = if (expanded) records.asReversed() else records.takeLast(1).asReversed()
        visible.forEach { record ->
            text("서버 보관 ${date(record.receivedAt)}", 18f, true)
            summary(record.samples.last())
            text(
                "${if (record.environment == "pleos_emulator") "에뮬레이터 관측" else "차량 관측 · 차량 증명 미확인"} · 전송 측 표시",
                16f
            )
            text(
                "클라이언트 시작 ${date(record.capturedAt)} · ${record.samples.size}개 중 마지막 표본 표시 · 시작 후 ${record.samples.last().elapsedMs}ms · SDK ${record.sdkVersion}",
                15f
            )
        }
        if (records.size > 1) button(if (expanded) "마지막 보관 1건만 보기" else "다른 관측 ${records.size - 1}건 보기") {
            expanded = !expanded
            render()
        }
        text("사건 발생 당시 값이 아닐 수 있습니다. 차량 신호의 출처를 인증한 자료가 아니며 기존 판정·원본·ZIP은 바뀌지 않습니다.", 16f)
        if (!writable && automotive && snapshot != null) text(
            "추가 관측 저장은 서비스 설정·계정 권한·자료 환경이 맞을 때 제공됩니다.",
            16f
        )
        button("관측 목록 새로 확인") { load() }
    }

    private fun preview() {
        if (!allowed() || lease == null) {
            clearPrivate()
            return
        }
        try {
            val current = checkNotNull(snapshot).invoke()
            require(current.opt("fresh_gear_received") == true && current.optString("gear") == "GEAR_PARK")
            pending = SdkObservation.create(current, UUID.randomUUID().toString(), emulator)
            notice = ""
            uncertain = false
            renderPending()
        } catch (_: Exception) {
            notice = "현재 P단 관측을 확인하지 못했습니다. 가상 값으로 대신하지 않았습니다."
            render()
        }
    }

    private fun renderPending() {
        val body = checkNotNull(pending)
        heading(if (uncertain) "보관 결과 미확인 · 같은 요청으로 재확인" else "저장 전 확인 · 아직 서버에 보관하지 않았습니다.")
        val sample = body.getJSONArray("samples").getJSONObject(0)
        val gear = sample.getJSONObject("gear")
        val speed = sample.getJSONObject("speed")
        summary(
            SdkObservationRecord.Sample(
                0,
                if (gear.isNull("value")) null else gear.getString("value"),
                if (gear.isNull("age_ms")) null else gear.getLong("age_ms"),
                if (speed.isNull("kmh")) null else speed.getDouble("kmh"),
                if (speed.isNull("age_ms")) null else speed.getLong("age_ms")
            )
        )
        text(if (emulator) "에뮬레이터 자료로 표시합니다." else "차량 자료로 표시하며 차량 신호의 출처는 별도 인증되지 않았습니다.", 17f)
        text("지금 확인한 관측을 이 사건에 별도로 보관합니다. 위치·사진은 전송하지 않고 원본·사건 판정·ZIP을 바꾸지 않습니다.", 17f)
        if (notice.isNotEmpty()) addView(ui.notice(notice))
        button(if (uncertain) "같은 자료로 보관 결과 재확인" else "이 사건에 추가 관측 저장", true) { save() }
        button("저장하지 않고 목록으로") {
            pending = null
            notice = ""
            render()
        }
    }

    private fun save() {
        if (!allowed() || lease?.valid(
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime()
            ) != true
        ) {
            clearPrivate()
            return
        }
        val body = checkNotNull(pending)
        val expected = session
        val attempt = ++generation
        heading("추가 관측 보관 결과를 확인하고 있습니다.")
        worker?.execute {
            val result = runCatching {
                AuthenticatedEventsClient(context).saveSdkObservationForDisplay(
                    eventId,
                    body,
                    expected
                )
            }
            post {
                if (!isAttachedToWindow || attempt != generation || !allowed()) return@post
                result.fold(onSuccess = {
                    pending = null
                    load("추가 관측을 보관했습니다. 원본이나 판정은 바뀌지 않았습니다.")
                }, onFailure = { failure ->
                    notice = message(failure, true)
                    val reason = (failure as? AccountAccessException)?.reason
                    if (reason in setOf(
                            AccountAccessException.Reason.SIGN_IN,
                            AccountAccessException.Reason.EXPIRED,
                            AccountAccessException.Reason.CHANGED
                        )
                    ) {
                        clearPrivate()
                    } else if (reason in setOf(
                            AccountAccessException.Reason.CONFLICT,
                            AccountAccessException.Reason.FORBIDDEN
                        )
                    ) {
                        pending = null
                        heading(notice)
                        button("목록 다시 조회", true) { load() }
                    } else {
                        uncertain = true
                        renderPending()
                    }
                })
            }
        }
    }
}
