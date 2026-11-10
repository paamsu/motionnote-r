package ai.teraq.roadguardian

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** 휴대폰에서 직접 눌러 읽는다. 백그라운드 조회, 저장, 진단, 업로드는 없다 */
open class PowertrainActivity : Activity() {
    private lateinit var ui: PhoneAccountUi
    private lateinit var body: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var first: EditText
    private lateinit var last: EditText
    private lateinit var lookup: Button
    private lateinit var form: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Handler(Looper.getMainLooper())
    private var eventId = ""
    private var vehicleId = ""
    private var session = ""
    private var generation = 0L
    private var foreground = false
    private var lease: AccountDisplayLease? = null
    private val watch = object : Runnable {
        override fun run() {
            if (session.isNotEmpty() && (runCatching { AuthenticatedEventsClient(this@PowertrainActivity).sessionFingerprint() != session }.getOrDefault(
                    true
                )
                        || lease?.valid(
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime()
                ) == false)
            ) expire()
            if (foreground) timer.postDelayed(this, 500)
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (!BuildConfig.POWERTRAIN_ENABLED || packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            finish()
            return
        }
        eventId =
            runCatching { AccountEventRoute.checked(intent.getStringExtra("event_id")) }.getOrNull()
                ?: run {
                    finish()
                    return
                }
        ui = PhoneAccountUi(this)
        body = ui.install()
        body.isSaveFromParentEnabled = false
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) {
            foreground = true
            prepare()
            timer.post(watch)
        }
    }

    override fun onPause() {
        foreground = false
        generation++
        session = ""
        lease = null
        clearScreen()
        timer.removeCallbacks(watch)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun clearScreen() {
        if (::results.isInitialized) results.removeAllViews()
        if (::first.isInitialized) first.text.clear()
        if (::last.isInitialized) last.text.clear()
        vehicleId = ""
        if (::body.isInitialized) body.removeAllViews()
    }

    private fun expire() {
        generation++
        session = ""
        lease = null
        clearScreen()
        body.addView(ui.notice("계정 또는 표시 기한이 바뀌어 자료를 지웠습니다."))
        back()
    }

    private fun back() {
        body.addView(ui.button("정비 기록으로 돌아가기") { finish() })
    }

    private fun current(expected: String) =
        foreground && !isFinishing && !isDestroyed && runCatching { AuthenticatedEventsClient(this).sessionFingerprint() == expected }.getOrDefault(
            false
        )

    private fun prepare() {
        val attempt = ++generation
        body.removeAllViews()
        ui.heading(body, "정비 기록의 Pleos 자료", "계정과 제공 범위를 확인하고 있습니다.")
        worker.execute {
            val result = runCatching {
                val client = AuthenticatedEventsClient(this)
                val event = client.eventForDisplay(eventId)
                val caps = client.capabilitiesForDisplay()
                check(
                    caps.value.powertrainRead && event.value.optString("source") == "owner_reported" && EvidenceScope.category(
                        event.value
                    ) == "maintenance" && event.value.optBoolean("evidence_valid")
                )
                check(event.sessionFingerprint == caps.sessionFingerprint)
                event
            }
            runOnUiThread {
                if (!foreground || attempt != generation) return@runOnUiThread
                result.fold(onSuccess = { received ->
                    if (!current(received.sessionFingerprint) || !received.lease.valid(
                            System.currentTimeMillis(),
                            SystemClock.elapsedRealtime()
                        )
                    ) {
                        expire()
                        return@fold
                    }
                    session = received.sessionFingerprint
                    lease = received.lease
                    val event = received.value
                    vehicleId = event.getJSONObject("data").getString("vehicle_id")
                    body.removeAllViews()
                    ui.heading(body, "정비 자료", event.getJSONObject("data").optString("title"))
                    val own = event.getJSONObject("data").optJSONObject("context")
                        ?.optJSONObject("own_vehicle")
                    body.addView(
                        ui.text(
                            "입력 차량 · " + (own?.optString("plate")?.takeIf { it.isNotBlank() }
                                ?: "번호 미기재"), 16f, true
                        )
                    )
                    body.addView(ui.text("Pleos Cloud · 유럽 지원 · 별도 제공 동의 필요", 15f))
                    form = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        isSaveFromParentEnabled = false
                    }
                    body.addView(form)
                    val created = event.optDouble("created", Double.NaN)
                    val date =
                        if (created.isFinite() && created > 0) Instant.ofEpochSecond(created.toLong())
                            .atOffset(ZoneOffset.UTC)
                            .toLocalDate() else LocalDate.now(ZoneOffset.UTC)

                    fun field(label: String): EditText {
                        form.addView(ui.text(label, 17f, true))
                        return EditText(this).apply {
                            inputType = InputType.TYPE_CLASS_NUMBER
                            filters = arrayOf(InputFilter.LengthFilter(8))
                            setSingleLine()
                            setTextColor(ui.ink)
                            setHintTextColor(ui.muted)
                            backgroundTintList =
                                android.content.res.ColorStateList.valueOf(ui.muted)
                            hint = "yyyyMMdd"
                            contentDescription = label
                            isSaveEnabled = false
                            importantForAutofill =
                                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                            imeOptions =
                                android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                            setText(date.format(DateTimeFormatter.BASIC_ISO_DATE))
                            form.addView(this, LinearLayout.LayoutParams(-1, ui.dp(56)))
                        }
                    }
                    first = field("시작 날짜 · UTC")
                    last = field("끝 날짜 · UTC")
                    form.addView(ui.text("최대 7일 차이 · 날짜 형식 예: 20261023", 15f))
                    lookup = ui.button("선택한 기간 조회", true) { query() }
                    form.addView(lookup)
                    results = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        isSaveFromParentEnabled = false
                    }
                    body.addView(results)
                    back()
                }, onFailure = { failure ->
                    body.removeAllViews()
                    body.addView(
                        ui.notice(
                            (failure as? AccountAccessException)?.message
                                ?: "이 정비 기록의 자료 제공 설정과 계정 권한을 확인해 주세요."
                        )
                    )
                    back()
                })
            }
        }
    }

    private fun query() {
        val start = first.text.toString()
        val end = last.text.toString()
        if (runCatching { PowertrainBatch.checkedDates(start, end) }.isFailure) {
            results.removeAllViews()
            results.addView(ui.notice("날짜를 yyyyMMdd 형식과 최대 7일 차이로 입력해 주세요."))
            return
        }
        if (!current(session)) {
            expire()
            return
        }
        val expected = session
        val vehicle = vehicleId
        val attempt = ++generation
        lookup.isEnabled = false
        first.isEnabled = false
        last.isEnabled = false
        results.removeAllViews()
        results.addView(ui.notice("선택한 기간의 자료를 조회하고 있습니다."))
        worker.execute {
            val received = runCatching {
                AuthenticatedEventsClient(this).powertrainForDisplay(
                    eventId,
                    vehicle,
                    start,
                    end
                )
            }
            runOnUiThread {
                if (attempt != generation || !current(expected)) return@runOnUiThread
                lookup.isEnabled = true
                first.isEnabled = true
                last.isEnabled = true
                results.removeAllViews()
                received.fold(
                    onSuccess = { response ->
                        if (response.sessionFingerprint != expected || !response.lease.valid(
                                System.currentTimeMillis(),
                                SystemClock.elapsedRealtime()
                            )
                        ) {
                            expire()
                            return@fold
                        }
                        lease = response.lease
                        render(response.value, start, end)
                    },
                    onFailure = { failure ->
                        results.addView(
                            ui.notice(
                                (failure as? AccountAccessException)?.message
                                    ?: "자료를 받을 수 없습니다. 제공 동의와 연결 상태를 확인해 주세요."
                            )
                        )
                    })
            }
        }
    }

    private fun render(batch: PowertrainBatch, start: String, end: String) {
        form.visibility = View.GONE
        fun date(value: String) = "${value.take(4)}.${value.substring(4, 6)}.${value.takeLast(2)}"
        results.addView(ui.text("${date(start)} ~ ${date(end)} · UTC", 20f, true))
        results.addView(ui.text("운행 ${batch.trips.size}건 · 자동 진단 미실시", 16f))
        results.addView(ui.button("조회 기간 바꾸기") {
            form.visibility = View.VISIBLE;
            (body.parent as? ScrollView)?.scrollTo(0, 0)
        })
        results.addView(ui.text("고전압 팩 · 표본 시각과 동기화 미확인", 16f, true))
        if (batch.trips.isEmpty()) {
            results.addView(ui.notice("선택한 기간에 반환된 운행 자료가 없습니다."))
            return
        }
        val charts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun show(index: Int) {
            charts.removeAllViews()
            val trip = batch.trips[index]
            charts.addView(
                ui.text(
                    Instant.parse(trip.start).atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm 'UTC'")), 17f, true
                )
            )
            for (signal in trip.signals) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(ui.cardColor)
                        cornerRadius = ui.dp(12).toFloat()
                    }
                }
                charts.addView(
                    card,
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(16) })
                card.addView(ui.text("${signal.label} · ${signal.unit}", 21f, true))
                val valid = signal.values.filterNotNull()
                if (valid.isEmpty()) card.addView(ui.notice(if (signal.returned) "유효한 표본이 없습니다." else "공급자가 이 항목을 반환하지 않았습니다."))
                else {
                    fun number(value: Double) = String.format(Locale.KOREAN, "%.2f", value)
                    card.addView(
                        ui.text(
                            "${number(valid.min())} ~ ${number(valid.max())} ${signal.unit}",
                            26f,
                            true
                        )
                    )
                    card.addView(
                        ui.text(
                            "유효 ${valid.size}/${signal.values.size}개 · 범위 밖 ${signal.invalid}개",
                            16f
                        )
                    )
                    if (resources.configuration.fontScale < 1.3f) card.addView(
                        PowertrainSignalChart(
                            this,
                            signal,
                            ui.light
                        ), LinearLayout.LayoutParams(-1, ui.dp(150))
                    )
                }
            }
        }
        if (batch.trips.size > 1) {
            val select = Spinner(this).apply {
                contentDescription = "조회한 운행 선택"
                adapter = ArrayAdapter(
                    this@PowertrainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    batch.trips.mapIndexed { i, t -> "${i + 1}. ${t.start}" })
            }
            select.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    show(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            results.addView(select)
        }
        results.addView(charts)
        show(0)
        val source = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        source.addView(
            ui.text(
                "선택 기간의 공급자 응답이며 차량의 현재 상태나 사건 당시 측정을 확인한 자료가 아닙니다. OEM 서명도 아닙니다.",
                16f
            )
        )
        source.addView(
            ui.text(
                "고전압 팩 자료입니다. 12V 배터리 진단에 사용하지 않습니다. 표본 시각·주기·동기화가 없어 정비 전후 비교를 보류합니다.",
                16f
            )
        )
        ui.disclosure(results, "자료 출처와 비교 조건", source)
    }
}
