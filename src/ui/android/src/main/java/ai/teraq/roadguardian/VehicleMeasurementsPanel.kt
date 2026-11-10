package ai.teraq.roadguardian

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.concurrent.Executors

/** 개인 측정값 선택 화면(읽기 전용). 정차·포그라운드·UX 정책은 MainActivity가 맡는다 */
class VehicleMeasurementsPanel(
    context: Context,
    private val ui: PhoneAccountUi,
    private val eventId: String,
    private val expectedCaseHash: String,
    private val allowed: () -> Boolean
) : LinearLayout(context) {
    private var worker: java.util.concurrent.ExecutorService? = null
    private val timer = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var session = ""
    private var lease: AccountDisplayLease? = null
    private var cleared = false
    private var vehicle = ""
    private var identity = ""
    private var records = emptyList<MeasurementProtocol.Summary>()
    private var before: MeasurementProtocol.Summary? = null
    private var after: MeasurementProtocol.Summary? = null
    private val scale = (resources.configuration.screenWidthDp / 1100f).coerceIn(1f, 1.65f)
    private fun text(value: String, size: Float = 18f, bold: Boolean = false) =
        ui.text(value, size * scale, bold)

    private fun recordButton(value: String, action: () -> Unit) =
        ui.button(value, action = action).apply {
            setSingleLine(false)
            maxLines = 8
            ellipsize = null
            setHorizontallyScrolling(false)
            minHeight = ui.dp(132)
            minimumHeight = minHeight
            maxHeight = Int.MAX_VALUE
        }

    private fun valid(expected: String) =
        isAttachedToWindow && allowed() && expected.isNotEmpty() &&
                lease?.valid(System.currentTimeMillis(), SystemClock.elapsedRealtime()) == true &&
                runCatching { AuthenticatedEventsClient(context).sessionFingerprint() == expected }.getOrDefault(
                    false
                )

    private val watch = object : Runnable {
        override fun run() {
            if (!allowed() || session.isNotEmpty() && !valid(session)) clearPrivate()
            if (isAttachedToWindow) timer.postDelayed(this, 500)
        }
    }

    init {
        orientation = VERTICAL
        isSaveFromParentEnabled = false
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
        erase()
        super.onDetachedFromWindow()
    }

    private fun erase() {
        session = ""
        lease = null
        records = emptyList()
        before = null
        after = null
        vehicle = ""
        identity = ""
        removeAllViews()
    }

    private fun clearPrivate() {
        if (cleared) return
        cleared = true
        generation++
        erase()
        visibility = VISIBLE
        addView(text("표시 조건이나 계정이 바뀌어 측정 자료를 지웠습니다.", 20f))
    }

    private fun heading() {
        removeAllViews()
        addView(text("앞·뒤 측정 비교", 28f, true))
        addView(text(identity, 19f, true))
    }

    private fun reveal() {
        post {
            if (isAttachedToWindow) requestRectangleOnScreen(
                android.graphics.Rect(
                    0,
                    0,
                    width,
                    minOf(height, ui.dp(440))
                ), false
            )
        }
    }

    private fun load() {
        if (!BuildConfig.MEASUREMENTS_ENABLED || !allowed()) {
            clearPrivate()
            return
        }
        val attempt = ++generation
        cleared = false
        erase()
        addView(text("측정 기록 확인 중", 21f))
        worker?.execute {
            data class Loaded(
                val event: AuthenticatedEventsClient.DisplayResult<JSONObject>,
                val rows: AuthenticatedEventsClient.DisplayResult<List<MeasurementProtocol.Summary>>
            )

            val result = runCatching {
                val client = AuthenticatedEventsClient(context)
                val caps = client.capabilitiesForDisplay()
                if (!caps.value.measurementsRead) throw AccountAccessException(
                    AccountAccessException.Reason.NOT_SUPPORTED
                )
                val event = client.eventForDisplay(eventId)
                val data = event.value
                require(
                    data.optString("source") == "owner_reported" && data.optBoolean("evidence_valid") && EvidenceScope.category(
                        data
                    ) == "maintenance" && data.getString("hash") == expectedCaseHash
                )
                val rows = client.measurementsForDisplay(eventId)
                require(caps.sessionFingerprint == event.sessionFingerprint && rows.sessionFingerprint == event.sessionFingerprint)
                Loaded(event, rows)
            }
            post {
                if (!isAttachedToWindow || attempt != generation || !allowed()) return@post
                result.fold(onSuccess = { loaded ->
                    session = loaded.rows.sessionFingerprint
                    lease = loaded.rows.lease
                    if (!valid(session)) {
                        clearPrivate()
                        return@fold
                    }
                    val data = loaded.event.value.getJSONObject("data")
                    vehicle = data.getString("vehicle_id")
                    identity = data.getJSONObject("context").getJSONObject("own_vehicle")
                        .optString("plate").takeIf { it.isNotBlank() && it != "null" } ?: "차량번호 미기재"
                    records =
                        loaded.rows.value.sortedWith(compareBy<MeasurementProtocol.Summary> { it.started }.thenBy { it.id })
                    selection()
                }, onFailure = { failure ->
                    erase()
                    if ((failure as? AccountAccessException)?.reason == AccountAccessException.Reason.NOT_SUPPORTED) visibility =
                        GONE
                    else addView(text("측정 기록을 받지 못했습니다. 사건을 다시 확인해 주세요.", 20f))
                })
            }
        }
    }

    private fun label(run: MeasurementProtocol.Summary) =
        MeasurementComparisonPanel.recordLabel(run, records.indexOfFirst { it.id == run.id } + 1)

    private fun selection() {
        if (!valid(session)) {
            clearPrivate()
            return
        }
        heading()
        if (records.size < 2) {
            addView(text("비교할 두 기록이 필요합니다. 파일 입력은 휴대전화에서 진행하세요.", 21f))
            return
        }
        addView(recordButton(before?.let { "앞 · ${label(it)}" } ?: "앞 기록 선택") { choose(true) })
        addView(recordButton(after?.let { "뒤 · ${label(it)}" } ?: "뒤 기록 선택") { choose(false) })
        addView(ui.button("선택한 두 기록 비교", true) { compare() }
            .apply { isEnabled = before != null && after != null && before?.id != after?.id })
        addView(text("기록 입력·파일 저장은 휴대전화에서", 16f))
    }

    private fun choose(front: Boolean) {
        if (!valid(session)) {
            clearPrivate()
            return
        }
        heading()
        addView(text(if (front) "앞 기록 선택" else "뒤 기록 선택", 24f, true))
        for (run in records) addView(recordButton(label(run)) {
            if (!valid(session)) {
                clearPrivate()
                return@recordButton
            }
            if (front) before = run else after = run
            selection()
            reveal()
        })
        addView(ui.button("선택 취소") {
            selection()
            reveal()
        })
        reveal()
    }

    private fun compare() {
        if (!valid(session)) {
            clearPrivate()
            return
        }
        val first = before ?: return
        val second = after ?: return
        if (first.id == second.id) return
        val expected = session
        val expectedVehicle = vehicle
        val attempt = ++generation
        heading()
        addView(text("선택한 두 기록 비교 중", 21f))
        reveal()
        worker?.execute {
            val result = runCatching {
                val client = AuthenticatedEventsClient(context)
                val a = client.measurementForDisplay(eventId, first.id, expectedVehicle)
                val b = client.measurementForDisplay(eventId, second.id, expectedVehicle)
                require(a.sessionFingerprint == expected && b.sessionFingerprint == expected && a.value.summary.hash == first.hash && b.value.summary.hash == second.hash)
                client.measurementComparisonForDisplay(eventId, expectedCaseHash, a.value, b.value)
            }
            post {
                if (!isAttachedToWindow || generation != attempt) return@post
                if (!valid(expected)) {
                    clearPrivate()
                    return@post
                }
                result.fold(onSuccess = { received ->
                    if (received.sessionFingerprint != expected || !received.lease.valid(
                            System.currentTimeMillis(),
                            SystemClock.elapsedRealtime()
                        )
                    ) {
                        clearPrivate()
                        return@fold
                    }
                    lease = received.lease
                    heading()
                    addView(ui.button("비교할 기록 다시 선택") {
                        selection()
                        reveal()
                    })
                    MeasurementComparisonPanel.render(ui, this, received.value, scale)
                    reveal()
                }, onFailure = {
                    heading()
                    addView(text("비교 결과를 확인하지 못했습니다. 기록을 다시 선택해 주세요.", 21f))
                    addView(ui.button("기록 다시 선택") { selection() })
                })
            }
        }
    }
}
