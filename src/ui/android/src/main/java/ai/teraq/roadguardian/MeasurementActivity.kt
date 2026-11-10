package ai.teraq.roadguardian

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** 휴대폰에서 직접 불러오기, 미리보기, 저장, 비교 보기. 임시 파일과 자동 재시도는 없다 */
open class MeasurementActivity : Activity() {
    private lateinit var ui: PhoneAccountUi
    private lateinit var body: LinearLayout
    private lateinit var outcome: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Handler(Looper.getMainLooper())

    @Volatile
    private var foreground = false
    private var generation = 0L
    private var eventId = ""
    private var vehicle = ""
    private var caseHash = ""
    private var session = ""
    private var pickerSession = ""
    private var choosing = false
    private var selectedUri: Uri? = null
    private var lease: AccountDisplayLease? = null
    private var pending: JSONObject? = null
    private var label = ""
    private var records = emptyList<MeasurementProtocol.Summary>()
    private var exportAllowed = false

    private data class ExportSelection(
        val caseHash: String,
        val vehicle: String,
        val before: MeasurementProtocol.Summary,
        val after: MeasurementProtocol.Summary,
        val session: String
    )

    private var exportSelection: ExportSelection? = null
    private var exportDestination: Uri? = null
    private val exportCancelled = java.util.concurrent.atomic.AtomicBoolean(true)
    private val watch = object : Runnable {
        override fun run() {
            if (session.isNotEmpty() && (!current(session) || lease?.valid(
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime()
                ) == false)
            ) expire()
            if (foreground) timer.postDelayed(this, 500)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!BuildConfig.MEASUREMENTS_ENABLED || packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
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
            if (!choosing) {
                if (exportDestination != null) consumeExport() else consumeSelection()
            }
            timer.post(watch)
        }
    }

    override fun onPause() {
        foreground = false
        exportCancelled.set(true)
        generation++
        session = ""
        lease = null
        clear()
        if (!choosing) {
            pickerSession = ""
            selectedUri = null
            exportSelection = null
            exportDestination = null
        }
        timer.removeCallbacks(watch)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun current(expected: String) =
        foreground && !isFinishing && !isDestroyed && runCatching { AuthenticatedEventsClient(this).sessionFingerprint() == expected }.getOrDefault(
            false
        )

    private fun clear() {
        exportAllowed = false
        pending = null
        records = emptyList()
        vehicle = ""
        caseHash = ""
        label = ""
        if (::outcome.isInitialized) outcome.removeAllViews()
        if (::body.isInitialized) body.removeAllViews()
    }

    private fun expire() {
        generation++
        session = ""
        lease = null
        pickerSession = ""
        selectedUri = null
        exportSelection = null
        exportDestination = null
        exportCancelled.set(true)
        clear()
        body.addView(ui.notice("계정 또는 표시 기한이 바뀌어 측정 자료를 지웠습니다."))
        back()
    }

    private fun back() {
        body.addView(ui.button("정비 기록으로 돌아가기") { finish() })
    }

    private fun heading() {
        body.removeAllViews()
        ui.heading(body, "앞·뒤 측정 비교", label)
        body.addView(ui.text("입력 조건의 대응 · 실제 조건과 수리 결과는 미확인", 15f))
    }

    private fun consumeSelection() {
        val uri = selectedUri
        val expected = pickerSession
        selectedUri = null
        pickerSession = ""
        load(uri, expected.takeIf { uri != null })
    }

    private fun load(uri: Uri? = null, expectedPicker: String? = null) {
        val attempt = ++generation
        clear()
        body.addView(ui.notice("정비 기록과 측정 보관 상태를 확인하고 있습니다."))
        worker.execute {
            data class Loaded(
                val event: AuthenticatedEventsClient.DisplayResult<JSONObject>,
                val capabilities: ServiceCapabilities,
                val rows: List<MeasurementProtocol.Summary>,
                val draft: JSONObject?
            )

            val result = runCatching {
                val client = AuthenticatedEventsClient(this)
                val event = client.eventForDisplay(eventId)
                val caps = client.capabilitiesForDisplay()
                if (!caps.value.measurementsRead) throw AccountAccessException(
                    AccountAccessException.Reason.NOT_SUPPORTED
                )
                require(
                    event.value.optString("source") == "owner_reported" && EvidenceScope.category(
                        event.value
                    ) == "maintenance" && event.value.optBoolean("evidence_valid")
                )
                val rows = client.measurementsForDisplay(eventId)
                require(event.sessionFingerprint == caps.sessionFingerprint && event.sessionFingerprint == rows.sessionFingerprint)
                val vehicleId = event.value.getJSONObject("data").getString("vehicle_id")
                val draft = if (uri == null) null else {
                    if (expectedPicker != event.sessionFingerprint || !caps.value.measurementsWrite || !client.canSaveMeasurements()) throw AccountAccessException(
                        AccountAccessException.Reason.CHANGED
                    )
                    readFile(uri, vehicleId)
                }
                Loaded(
                    event,
                    caps.value,
                    rows.value.sortedWith(compareBy<MeasurementProtocol.Summary> { it.started }.thenBy { it.id }),
                    draft
                )
            }
            runOnUiThread {
                if (!foreground || attempt != generation) return@runOnUiThread
                result.fold(onSuccess = { loaded ->
                    if (!current(loaded.event.sessionFingerprint) || !loaded.event.lease.valid(
                            System.currentTimeMillis(),
                            SystemClock.elapsedRealtime()
                        )
                    ) {
                        expire()
                        return@fold
                    }
                    exportAllowed = loaded.capabilities.measurementsExport
                    session = loaded.event.sessionFingerprint
                    lease = loaded.event.lease
                    val event = loaded.event.value
                    val data = event.getJSONObject("data")
                    vehicle = data.getString("vehicle_id")
                    caseHash = event.getString("hash")
                    label = data.getString("title") + "\n입력 차량 · " + (data.optJSONObject("context")
                        ?.optJSONObject("own_vehicle")?.optString("plate")
                        ?.takeIf { it.isNotBlank() } ?: "번호 미기재")
                    records = loaded.rows
                    if (loaded.draft != null) {
                        pending = loaded.draft
                        preview()
                    } else showRecords(loaded.capabilities.measurementsWrite)
                }, onFailure = { failure ->
                    body.removeAllViews()
                    body.addView(
                        ui.notice(
                            (failure as? AccountAccessException)?.message
                                ?: "측정 파일의 형식·차량 키·자료 제공 범위를 확인해 주세요."
                        )
                    )
                    body.addView(ui.button("보관 목록 다시 확인") { load() })
                    back()
                })
            }
        }
    }

    private fun readFile(uri: Uri, vehicleId: String): JSONObject {
        require(uri.scheme == "content")
        val bytes = contentResolver.openInputStream(uri)?.use { source ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 256 * 1024) {
                val read =
                    source.read(buffer, 0, minOf(buffer.size, 256 * 1024 + 1 - output.size()))
                if (read < 0) break
                if (read == 0) throw IllegalArgumentException("파일 읽기 실패")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("파일 읽기 실패")
        return MeasurementProtocol.validate(MeasurementProtocol.decode(bytes), vehicleId)
    }

    private fun chooseFile() {
        if (!current(session)) {
            expire()
            return
        }
        pickerSession = session
        choosing = true
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain")),
                441
            )
        } catch (_: Exception) {
            choosing = false
            pickerSession = ""
            body.addView(ui.notice("파일 선택창을 열지 못했습니다."))
        }
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 442) {
            choosing = false
            val selection = exportSelection
            val valid =
                selection != null && runCatching { AuthenticatedEventsClient(this).sessionFingerprint() == selection.session }.getOrDefault(
                    false
                )
            exportDestination =
                if (valid && result == RESULT_OK) data?.data?.takeIf { it.scheme == "content" } else null
            if (exportDestination == null) exportSelection = null
            if (foreground) {
                if (exportDestination != null) consumeExport() else load()
            }
            return
        }
        if (request != 441) return
        choosing = false
        val valid =
            runCatching { AuthenticatedEventsClient(this).sessionFingerprint() == pickerSession && pickerSession.isNotEmpty() }.getOrDefault(
                false
            )
        selectedUri = if (valid && result == RESULT_OK) data?.data else null
        if (foreground) consumeSelection()
    }

    private fun date(value: Double) = Instant.ofEpochSecond(value.toLong()).atOffset(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss 'UTC'"))

    private fun source(value: String) = if (value == "synthetic") "합성 측정 시험" else "외부 입력 · 실측 증명 아님"
    private fun circuit(value: String) = if (value == "auxiliary_12v") "보조 12V" else "고전압 팩"
    private fun showRecords(writable: Boolean) {
        heading()
        body.addView(ui.text("보관 ${records.size}/16건", 21f, true))
        if (writable) body.addView(ui.button("측정 JSON 파일 확인") { chooseFile() })
        body.addView(ui.text("측정 비교 묶음은 비교 결과에서 따로 저장합니다.", 15f))
        if (records.size < 2) {
            body.addView(
                CaseFlowGraphic(
                    this,
                    listOf(
                        CaseFlowGraphic.Step(
                            "앞 자료",
                            if (records.isEmpty()) "보관 전" else "자료 있음",
                            if (records.isEmpty()) 0 else 1
                        ),
                        CaseFlowGraphic.Step("뒤 자료", "보관 전", 0),
                        CaseFlowGraphic.Step("조건 비교", "자료 대기", 0)
                    ),
                    ui.light,
                    1f
                ), LinearLayout.LayoutParams(-1, ui.dp(118))
            )
            body.addView(ui.notice("비교할 두 기록이 필요합니다. 표본 시각과 측정 조건이 있는 파일을 확인해 보관하세요."))
        } else {
            val labels = records.mapIndexed { index, run ->
                MeasurementComparisonPanel.recordLabel(
                    run,
                    index + 1
                )
            }

            fun select(title: String, position: Int): Spinner {
                body.addView(ui.text(title, 17f, true))
                val choices = object : ArrayAdapter<String>(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    labels
                ) {
                    private fun row(index: Int) = TextView(this@MeasurementActivity).apply {
                        text = labels[index]
                        textSize = 16f
                        setTextColor(ui.ink)
                        setBackgroundColor(ui.cardColor)
                        typeface = PleosTypography.face()
                        setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                        maxLines = 4
                        minimumHeight = ui.dp(80)
                    }

                    override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                        row(position)

                    override fun getDropDownView(
                        position: Int,
                        convertView: View?,
                        parent: ViewGroup
                    ) = row(position)
                }
                return Spinner(this).apply {
                    contentDescription = title
                    adapter = choices
                    minimumHeight = ui.dp(88)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(ui.muted)
                    setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(ui.cardColor))
                    setSelection(position)
                    body.addView(this, LinearLayout.LayoutParams(-1, -2))
                }
            }

            val before = select("앞 측정 기록", 0)
            val after = select("뒤 측정 기록", records.lastIndex)
            body.addView(
                ui.button(
                    "선택한 두 기록 비교",
                    true
                ) {
                    compare(
                        records[before.selectedItemPosition].id,
                        records[after.selectedItemPosition].id
                    )
                })
        }
        outcome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isSaveFromParentEnabled = false
        }
        body.addView(outcome)
        back()
    }

    private fun preview() {
        val draft = pending ?: return
        heading()
        body.addView(ui.text("보관 전 확인", 24f, true))
        body.addView(
            ui.text(
                source(draft.getString("environment")) + " · " + circuit(
                    draft.getString(
                        "channel"
                    )
                ), 20f, true
            )
        )
        body.addView(
            ui.text(
                "${date(draft.getDouble("started_at"))}\n표본 ${
                    draft.getJSONArray("samples").length()
                }개 · 명목 주기 ${draft.getInt("sample_period_ms")} ms", 18f
            )
        )
        body.addView(ui.notice("선택한 파일을 이 계정의 정비 기록으로 전송합니다. 차량·실측·교정 유효성은 확인되지 않았습니다. 기존 기록은 덮어쓰지 않습니다."))
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        details.addView(
            ui.text(
                "측정기 · ${draft.getString("instrument_id")}\n교정 참조 · ${
                    if (draft.isNull(
                            "calibration_ref"
                        )
                    ) "미기재" else draft.getString("calibration_ref")
                }\n기록 ID · ${draft.getString("run_id")}", 16f
            )
        )
        ui.disclosure(body, "입력 출처와 기록 번호", details)
        body.addView(ui.button("이 측정 기록을 보관", true) { savePending() })
        body.addView(ui.button("전송하지 않고 목록으로") {
            pending = null
            load()
        })
        back()
    }

    private fun savePending() {
        val draft = pending ?: return
        if (!current(session)) {
            expire()
            return
        }
        val expected = session
        val vehicleId = vehicle
        val attempt = ++generation
        heading()
        body.addView(ui.notice("선택한 측정 기록의 저장 결과를 확인하고 있습니다."))
        worker.execute {
            val saved = runCatching {
                AuthenticatedEventsClient(this).saveMeasurementForDisplay(
                    eventId,
                    vehicleId,
                    draft,
                    expected
                )
            }
            runOnUiThread {
                if (attempt != generation || !current(expected)) return@runOnUiThread
                saved.fold(onSuccess = {
                    pending = null
                    load()
                }, onFailure = {
                    heading()
                    body.addView(ui.notice("저장 결과를 확인하지 못했습니다. 같은 기록 ID로 다시 확인하거나 보관 목록을 새로 조회하세요."))
                    body.addView(ui.button("같은 내용의 저장 다시 확인", true) { savePending() })
                    body.addView(ui.button("보관 목록 새로 확인") {
                        pending = null
                        load()
                    })
                    back()
                })
            }
        }
    }

    private fun compare(beforeId: String, afterId: String) {
        if (beforeId == afterId) {
            outcome.removeAllViews()
            outcome.addView(ui.notice("서로 다른 두 기록을 선택해 주세요."))
            return
        }
        if (!current(session)) {
            expire()
            return
        }
        val expected = session
        val vehicleId = vehicle
        val originalHash = caseHash
        val attempt = ++generation
        outcome.removeAllViews()
        outcome.addView(ui.notice("선택한 기록과 조건 대응을 확인하고 있습니다."))
        worker.execute {
            val compared = runCatching {
                val client = AuthenticatedEventsClient(this)
                val first = client.measurementForDisplay(eventId, beforeId, vehicleId)
                val second = client.measurementForDisplay(eventId, afterId, vehicleId)
                require(first.sessionFingerprint == expected && second.sessionFingerprint == expected)
                client.measurementComparisonForDisplay(
                    eventId,
                    originalHash,
                    first.value,
                    second.value
                )
            }
            runOnUiThread {
                if (attempt != generation || !current(expected)) return@runOnUiThread
                outcome.removeAllViews()
                compared.fold(
                    onSuccess = { received ->
                        if (received.sessionFingerprint != expected || !received.lease.valid(
                                System.currentTimeMillis(),
                                SystemClock.elapsedRealtime()
                            )
                        ) {
                            expire()
                            return@fold
                        }
                        lease = received.lease
                        heading()
                        body.addView(ui.button("비교할 기록 다시 선택") { load() })
                        outcome = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            isSaveFromParentEnabled = false
                        }
                        body.addView(outcome)
                        showComparison(received.value)
                        back()
                    },
                    onFailure = { failure ->
                        outcome.addView(
                            ui.notice(
                                (failure as? AccountAccessException)?.message
                                    ?: "선택한 자료와 비교 조건을 확인하지 못했습니다. 목록을 다시 조회해 주세요."
                            )
                        )
                    })
            }
        }
    }

    private fun showComparison(comparison: MeasurementProtocol.Comparison) {
        MeasurementComparisonPanel.render(ui, outcome, comparison)
        outcome.addView(ui.text("측정 원문·비교 결과 저장 · 사진·메모 제외", 15f))
        if (exportAllowed) outcome.addView(ui.button("선택한 두 기록의 비교 묶음 저장") { chooseExport(comparison) })
    }

    private fun chooseExport(comparison: MeasurementProtocol.Comparison) {
        if (!exportAllowed || !current(session)) {
            expire()
            return
        }
        exportSelection = ExportSelection(
            caseHash,
            vehicle,
            comparison.before.summary,
            comparison.after.summary,
            session
        )
        choosing = true
        try {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "naepyeon-measurement-${eventId.take(8)}.zip"),
                442
            )
        } catch (_: Exception) {
            choosing = false
            exportSelection = null
            outcome.addView(ui.notice("저장 위치 선택창을 열지 못했습니다."))
        }
    }

    private fun consumeExport() {
        val selection = exportSelection
        val destination = exportDestination
        exportSelection = null
        exportDestination = null
        if (selection == null || destination == null) {
            load()
            return
        }
        val attempt = ++generation
        clear()
        body.addView(ui.notice("선택한 원문·비교 결과·파일 목록을 확인한 뒤 저장하고 있습니다."))
        exportCancelled.set(false)
        worker.execute {
            val result = runCatching {
                val client = AuthenticatedEventsClient(this)
                fun allowed() {
                    if (exportCancelled.get() || !foreground || client.sessionFingerprint() != selection.session) throw AccountAccessException(
                        AccountAccessException.Reason.CHANGED
                    )
                }
                allowed()
                val payload = client.measurementPackage(
                    eventId,
                    selection.caseHash,
                    selection.vehicle,
                    selection.before,
                    selection.after
                )
                try {
                    allowed()
                    val output = contentResolver.openOutputStream(destination, "wt")
                        ?: throw java.io.IOException()
                    output.use { out ->
                        payload.inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                allowed()
                                val count = input.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                            }
                        }
                    }
                    allowed()
                } finally {
                    payload.delete()
                }
            }
            runOnUiThread {
                if (!foreground || generation != attempt) return@runOnUiThread
                body.removeAllViews()
                if (result.isSuccess) {
                    body.addView(ui.text("비교 기록 저장 완료", 26f, true))
                    body.addView(
                        DriverEventGraphic(this, "record_saved", ui.light),
                        LinearLayout.LayoutParams(-1, ui.dp(150))
                    )
                    body.addView(ui.text("선택한 두 기록을 저장했습니다.", 20f))
                    ui.disclosure(body, "저장 내용 안내", LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            ui.text(
                                "선택한 두 측정 기록과 비교 결과의 파일 일치를 확인해 저장했습니다. 사실 확인·수리 성공·기관 제출을 뜻하지 않습니다.",
                                16f
                            )
                        )
                    })
                } else body.addView(ui.notice("비교 묶음을 저장하지 못했습니다. 연결과 계정을 확인해 다시 시도하세요. 선택한 위치에 빈 파일이나 일부만 저장된 파일이 남을 수 있습니다."))
                body.addView(ui.button("측정 목록 다시 확인") { load() })
                back()
            }
        }
    }

}
