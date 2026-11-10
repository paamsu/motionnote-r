package ai.teraq.roadguardian

import android.app.Activity
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val demoMode =
        (BuildConfig.DEBUG || BuildConfig.CONTEST_DEMO) && BuildConfig.SYNTHETIC_BACKEND
    private var pendingEventSession = false
    private val executor = Executors.newSingleThreadExecutor()
    private val client = BackendClient()
    private var uxMonitor: VehicleUxMonitor? = null
    private var locationMonitor: VehicleLocationMonitor? = null
    private var activeMapPanel: NativeMapPanel? = null
    private var renderedLocationKey = ""
    private fun locationKey(): String = locationMonitor?.current()
        ?.let { "${it.latitude},${it.longitude},${it.mock},${it.accuracy}" } ?: "unavailable"

    private lateinit var adapter: PleosAdapter
    private lateinit var voice: VoiceInput
    private var voiceListening = false
    private var voiceMessage = ""
    private var voiceFallback = false
    private var voicePermissionRequested = false
    private var pendingVoicePermission: Boolean? = null
    private var voicePermissionDenied = false
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var selected = "기록"
    private var events = JSONArray()
    private val accountRecords = VehicleRecordState()
    private val overviewSelection = VehicleOverviewSelection()
    private var roadSource: JSONObject? = null
    private var roadSourceParent: JSONObject? = null
    private var roadSourceFailed = false
    private var roadSourceLoading = false
    private var roadSourceGeneration = 0L
    private var diagnosticReviewParent: JSONObject? = null
    private var diagnosticReview: JSONObject? = null
    private var diagnosticReviewLoading = false
    private var diagnosticReviewFailed = false
    private var diagnosticReviewGeneration = 0L
    private var diagnosticReviewSelection: String? = null
    private var detailReturnId: String? = null
    private var detailReturnLoading = false
    private var detailReturnFailed = false
    private var detailReturnGeneration = 0L
    private fun openComparisonVideo(
        eventId: String,
        parentId: String,
        helpSession: String? = null
    ) {
        if (!demoMode || !foreground || driving || busy) return
        detailReturnId = parentId
        detailReturnFailed = false
        opened = null
        try {
            startActivity(
                android.content.Intent(this, VideoActivity::class.java)
                    .putExtra("event_id", eventId).putExtra("light", light)
                    .putExtra("help_video_session", helpSession)
            )
        } catch (_: Exception) {
            detailReturnFailed = true
            render()
        }
    }

    private fun refreshReturnedDetail() {
        val id = detailReturnId ?: return
        if (!foreground || detailReturnLoading || driving) return
        val ticket = ++detailReturnGeneration
        detailReturnLoading = true
        detailReturnFailed = false
        opened = null
        render()
        executor.execute {
            val result = runCatching {
                JSONObject(client.request("/api/events/$id")).also {
                    require(
                        it.optString("id") == id && it.optString("source") == "synthetic"
                    )
                }
            }
            runOnUiThread {
                if (ticket != detailReturnGeneration) return@runOnUiThread
                detailReturnLoading = false
                if (isDestroyed || !foreground || driving) return@runOnUiThread
                result.fold(onSuccess = {
                    opened = it
                    detailReturnId = null
                }, onFailure = { detailReturnFailed = true })
                render()
            }
        }
    }

    private fun requestEvidenceExport(event: JSONObject) {
        if (!demoMode || !foreground || driving || busy || event.opt("evidence_valid") != true) return
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            saveVehicleEvidence(event.getString("id"))
            return
        }
        pendingExportId = event.getString("id")
        try {
            startActivityForResult(
                android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/zip"
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    putExtra(
                        android.content.Intent.EXTRA_TITLE,
                        "R_비교자료_${event.getString("id").take(8)}.zip"
                    )
                }, 81
            )
        } catch (_: Exception) {
            pendingExportId = null
            Toast.makeText(this, "저장 위치 선택 창을 열지 못했습니다", Toast.LENGTH_LONG).show()
        }
    }

    private var activeExportId: String? = null
    private var exportButton: Button? = null
    private var exportButtonEventId: String? = null
    private fun exportLabel(id: String): String {
        if (activeExportId == id) return "기록 묶음 보관 중…"
        val preferences = getSharedPreferences("export_result", MODE_PRIVATE)
        return when (preferences.getString("state:$id", null)) {
            "failed" -> "보관 실패 · 다시 시도"
            "saved" -> {
                val name = preferences.getString("file:$id", null)
                if (name != null && java.io.File(
                        java.io.File(filesDir, "evidence-exports"),
                        name
                    ).isFile
                )
                    "보관 완료 · 다시 보관" else "기록 묶음 보관"
            }

            else -> "기록 묶음 보관"
        }
    }

    private fun refreshExportButton(id: String) {
        if (exportButtonEventId == id) exportButton?.let {
            it.text = exportLabel(id)
            it.isEnabled = activeExportId != id
        }
    }

    private fun saveVehicleEvidence(id: String) {
        busy = true
        activeExportId = id
        refreshExportButton(id)
        executor.execute {
            val message = try {
                val payload = client.evidencePackage(id)
                val checked = java.io.File.createTempFile("vehicle-export-", ".zip", cacheDir)
                val bundled = try {
                    checked.writeBytes(payload)
                    EventMapExport.bundle(checked, id, filesDir)
                } finally {
                    checked.delete()
                }
                val directory = java.io.File(filesDir, "evidence-exports")
                    .apply { check(mkdirs() || isDirectory) }
                val target = java.io.File(directory, "$id-${java.util.UUID.randomUUID()}.zip")
                val atomic = android.util.AtomicFile(target)
                var output: java.io.FileOutputStream? = null
                try {
                    output = atomic.startWrite()
                    output.write(bundled)
                    atomic.finishWrite(output)
                    output = null
                    getSharedPreferences("export_result", MODE_PRIVATE).edit()
                        .putString("last_vehicle_file", target.name)
                        .putString("file:$id", target.name).putString("state:$id", "saved").commit()
                } catch (t: Exception) {
                    output?.let { atomic.failWrite(it) }
                    throw t
                }
                "기록 묶음 보관 완료"
            } catch (_: Exception) {
                getSharedPreferences("export_result", MODE_PRIVATE).edit()
                    .putString("state:$id", "failed").commit()
                "기록을 보관하지 못했습니다. 다시 시도해 주세요"
            }
            runOnUiThread {
                busy = false
                activeExportId = null
                refreshExportButton(id)
                reportExportResult(message)
            }
        }
    }

    private var foreground = false
    private fun accountRecordsAllowed() =
        foreground && !driving && selected in setOf("기록", "차량") && !demoMode

    private var vehicleProfile: JSONObject? = null
    private var sharedFeed: JSONObject? = null
    private var roadSummary: JSONObject? = null
    private var roadGroupPage: JSONObject? = null
    private var sharingUnavailable = false
    private var feedLoading = false
    private val comparisonNotice = ComparisonNoticeState()
    private val driverEventNotice = DriverEventNoticeState()
    private val systemEventNotices = DemoEventMonitorState()
    private var driverBriefHost: FrameLayout? = null
    private fun driverEventVisible(now: Long): JSONObject? =
        if (demoMode && foreground && uxMonitor?.restricted == false &&
            drivingState.briefState(now) in setOf("drive", "simulated_drive")
        ) driverEventNotice.current(now) else null

    private var comparisonSpeech: ComparisonSpeech? = null
    private var comparisonSpeechEnabled = ComparisonSpeechPolicy.DEFAULT_ENABLED
    private fun updateComparisonSpeech() {
        val now = android.os.SystemClock.elapsedRealtime()
        comparisonSpeech?.update(
            comparisonNotice.sequence,
            comparisonSpeechAllowed(now),
            comparisonNotice.freshForSpeech(now)
        )
    }

    private fun comparisonSpeechAllowed(now: Long) = ComparisonSpeechPolicy.allowed(
        comparisonSpeechEnabled, comparisonNoticeVisible(now), drivingState.briefState(now)
    )

    private var comparisonLoading = false
    private var comparisonGeneration = 0L
    private fun comparisonNoticeVisible(now: Long): Boolean =
        demoMode && foreground && uxMonitor?.restricted == false &&
                drivingState.briefState(now) in setOf(
            "drive",
            "simulated_drive",
            "parked",
            "simulated_park"
        ) && comparisonNotice.active(now)

    private fun pendingComparisonCount(now: Long): Int = if (demoMode && foreground && !driving)
        comparisonNotice.pendingCount(now) else 0

    private var pendingOpenGeneration = 0L
    private fun openPendingComparison() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (pendingComparisonCount(now) == 0 || busy || detailReturnId != null) return
        val id = comparisonNotice.nextPending(now) ?: return
        val openTicket = ++pendingOpenGeneration
        val ticket = comparisonGeneration
        busy = true
        requestFailed = false
        render()
        executor.execute {
            val response = runCatching {
                JSONObject(client.request("/api/events/$id")).also {
                    require(
                        it.optString("id") == id && it.optString("source") == "synthetic" && EvidenceScope.canNotifyComparison(
                            it
                        )
                    )
                }
            }
            runOnUiThread {
                busy = false
                if (isDestroyed || !foreground || driving || ticket != comparisonGeneration || openTicket != pendingOpenGeneration) return@runOnUiThread
                response.fold(onSuccess = {
                    clearRoadSource()
                    opened = it
                    selected = "기록"
                    helpRequested = false
                    comparisonNotice.acknowledge(id)
                }, onFailure = { requestFailed = true })
                render()
            }
        }
    }

    private val timer = android.os.Handler(android.os.Looper.getMainLooper())
    private var renderedDrivingLabel = ""
    private var renderedRoadKey = ""
    private val drivingWatch = object : Runnable {
        override fun run() {
            updateComparisonSpeech()
            if (accountRecords.expire(
                    System.currentTimeMillis(),
                    android.os.SystemClock.elapsedRealtime()
                )
            ) render()
            val mapVisible = opened == null && !helpRequested && selected in listOf("홈", "도로")
            if (renderedParkingCue != parkingCueVisible() || renderedDrivingLabel != drivingViewKey(
                    android.os.SystemClock.elapsedRealtime()
                ) || (mapVisible && renderedRoadKey != roadKey())
            ) render()
            else if (mapVisible && renderedLocationKey != locationKey()) {
                val fix = locationMonitor?.current()
                if (fix != null && activeMapPanel?.updatePosition(fix) == true) renderedLocationKey =
                    locationKey()
                else render()
            }
            timer.postDelayed(this, 1000)
        }
    }
    private val feedPoll = object : Runnable {
        override fun run() {
            sendParkingArrival()
            pollFeed()
            pollComparisons()
            timer.postDelayed(this, 5000)
        }
    }
    private var sdkStatus = "Pleos 서비스 확인 중"
    private var busy = false
    private var requestFailed = false
    private val drivingState = DrivingState(BuildConfig.DEBUG && demoMode)
    private val parkingArrival = ParkingArrival()
    private var actualParkAt = -1L
    private var renderedParkingCue = false
    private var parkingPreview = false
    private var arrivalSending = false
    private fun sendParkingArrival() {
        if (!demoMode || arrivalSending || isDestroyed) return
        val prefs = getSharedPreferences("parking_arrival", MODE_PRIVATE)
        val payload = prefs.getString("relay_pending", null) ?: return
        arrivalSending = true
        executor.execute {
            val accepted = try {
                val sent = JSONObject(payload)
                val reply = JSONObject(client.request("/api/dev/parking-arrival", sent))
                reply.optBoolean("accepted") && reply.optString("id") == sent.optString("id")
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                arrivalSending = false
                if (accepted && prefs.getString("relay_pending", null) == payload) prefs.edit()
                    .remove("relay_pending").apply()
            }
        }
    }

    private fun parkingCueVisible(): Boolean {
        if (demoMode && parkingPreview) return true
        val pending = getSharedPreferences("parking_arrival", MODE_PRIVATE).getLong("pending_at", 0)
        return pending > 0 && System.currentTimeMillis() - pending in 0L..86_400_000L &&
                actualParkAt >= 0 && android.os.SystemClock.elapsedRealtime() - actualParkAt in 0L..4999L
    }

    private var simulatedDriving = false
    private fun drivingViewKey(now: Long) =
        drivingState.label(now) + "|" + drivingState.briefState(now) + "|" + comparisonNoticeVisible(
            now
        ) + "|" + pendingComparisonCount(now) + "|" + driverEventVisible(now)?.toString()

    private val driving
        get() = drivingState.restricted(android.os.SystemClock.elapsedRealtime()) || (uxMonitor?.restricted
            ?: true)
    private var light = false
    private var displayMode = "system"
    private fun applyDisplayMode() {
        light = when (displayMode) {
            "day" -> true
            "night" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }

    private var opened: JSONObject? = null
    private var pendingExportId: String? = null
    private var helpRequested = false
    private var helpTarget: String? = null
    private var showTestScenarios = false
    private val bg get() = PleosPalette.background(light)
    private val panel get() = PleosPalette.panel(light)
    private val ink get() = PleosPalette.ink(light)
    private val muted get() = PleosPalette.muted(light)
    private val blue get() = PleosPalette.action(light)
    private val amber get() = c(if (light) "#805000" else "#FFC777")
    private fun c(value: String) = Color.parseColor(value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val cockpitScale
        get() = (resources.configuration.screenWidthDp / 1400f).coerceIn(
            1f,
            1.65f
        )

    private fun cockpitDp(value: Int) = dp(kotlin.math.round(value * cockpitScale).toInt())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            startActivity(android.content.Intent(this, OwnerActivity::class.java))
            finish()
            return
        }
        if (demoMode) {
            comparisonSpeechEnabled =
                getSharedPreferences("comparison_speech", MODE_PRIVATE).getBoolean(
                    "enabled",
                    ComparisonSpeechPolicy.DEFAULT_ENABLED
                )
            comparisonSpeech = ComparisonSpeech(this) {
                val now = android.os.SystemClock.elapsedRealtime()
                comparisonSpeechAllowed(now) && comparisonNotice.freshForSpeech(now)
            }
        }
        parkingPreview =
            BuildConfig.DEBUG && demoMode && intent.getBooleanExtra("preview_parking_cue", false)
        locationMonitor = VehicleLocationMonitor(this)
        val display = getSharedPreferences("display", MODE_PRIVATE)
        displayMode = display.getString("mode", null) ?: if (display.contains("day")) {
            if (display.getBoolean("day", false)) "day" else "night"
        } else "system"
        applyDisplayMode()
        simulatedDriving = savedInstanceState?.getBoolean("driving") ?: false
        drivingState.setSimulatedMoving(simulatedDriving)
        drivingState.useSimulation(savedInstanceState?.getBoolean("simulation") ?: false)
        selected =
            savedInstanceState?.getString("tab")?.takeIf { it in listOf("기록", "차량", "설정") } ?: "기록"
        pendingExportId = savedInstanceState?.getString("export_event_id")
        detailReturnId = savedInstanceState?.getString("detail_return_id")
        if (demoMode) {
            overviewSelection.setCategory(savedInstanceState?.getString("overview_category"))
            overviewSelection.select(savedInstanceState?.getString("overview_event_id"))
        }
        adapter = PleosAdapter(this, {
            sdkStatus = it
            if (selected == "설정") render()
        }, { gear, observedAt ->
            val before = drivingViewKey(android.os.SystemClock.elapsedRealtime())
            val now = android.os.SystemClock.elapsedRealtime()
            drivingState.observe(gear, observedAt)
            actualParkAt = if (gear == "GEAR_PARK") observedAt else -1L
            val arrived = parkingArrival.observe(gear, observedAt)
            if (arrived) {
                val at = System.currentTimeMillis()
                val edit = getSharedPreferences("parking_arrival", MODE_PRIVATE).edit()
                    .putLong("pending_at", at)
                if (demoMode) edit.putString(
                    "relay_pending", JSONObject()
                        .put("id", UUID.randomUUID().toString()).put("parked_at", at / 1000.0)
                        .put("source", "pleos_emulator").toString()
                )
                edit.apply()
                sendParkingArrival()
            }
            if (arrived || before != drivingViewKey(now)) render()
        })
        render()
        uxMonitor = VehicleUxMonitor(this) { render() }.also { it.start() }
        adapter.start()
        voice = VoiceInput(this) { phrase -> handleVoiceResult(phrase) }
        if (demoMode && intent.getBooleanExtra("review_navigation_background", false)) {
            intent.removeExtra("review_navigation_background")
            startActivity(
                Intent(this, ContestNavigationActivity::class.java).putExtra(
                    "light",
                    light
                )
            )
        }
        val reviewId = if (demoMode) intent.getStringExtra("review_event_id") else null
        if (demoMode && intent.getBooleanExtra("review_road_summary", false)) task {
            roadSummary = JSONObject(client.request("/api/demo/road-summary"))
            selected = "집계"
        }
        else if (reviewId != null) task {
            events = JSONArray(client.request("/api/events"))
            vehicleProfile = JSONObject(client.request("/api/demo/vehicle"))
            opened = JSONObject(client.request("/api/events/$reviewId"))
            selected = "기록"
        } else refresh()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("export_event_id", pendingExportId)
        out.putString("detail_return_id", detailReturnId)
        if (demoMode) {
            out.putString("overview_category", overviewSelection.category)
            out.putString("overview_event_id", overviewSelection.eventId)
        }
        out.putBoolean("driving", simulatedDriving)
        out.putBoolean("simulation", drivingState.isSimulation)
        out.putString("tab", selected)
        super.onSaveInstanceState(out)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (pendingEventSession) {
            pendingEventSession = false
            startEventSession()
        }
        showPendingExportResult()
        if (!isFinishing) {
            if (::adapter.isInitialized) adapter.startTireMonitoring()
            locationMonitor?.start()
            timer.post(feedPoll)
            timer.post(drivingWatch)
            if (detailReturnId != null) refreshReturnedDetail() else if (selected in setOf(
                    "기록",
                    "차량"
                )
            ) render()
        }
    }

    override fun onPause() {
        foreground = false
        if (::voice.isInitialized) voice.close()
        voiceListening = false
        voiceMessage = ""
        voiceFallback = false
        exportResultToast?.cancel()
        exportResultToast = null
        if (diagnosticReviewParent != null && ::content.isInitialized) content.removeAllViews()
        clearDiagnosticReview()
        driverBriefHost?.removeAllViews()
        driverBriefHost = null
        driverEventNotice.invalidate()
        systemEventNotices.invalidate()
        if (demoMode && selected in setOf(
                "기록",
                "차량"
            ) && ::content.isInitialized
        ) content.removeAllViews()
        detailReturnGeneration++
        detailReturnLoading = false
        if (detailReturnId != null && ::content.isInitialized) content.removeAllViews()
        if (roadSourceParent != null && ::content.isInitialized) content.removeAllViews()
        clearRoadSource()
        comparisonGeneration++
        comparisonNotice.invalidate()
        comparisonSpeech?.stop()
        if (::adapter.isInitialized) adapter.stopTireMonitoring()
        accountRecords.clear()
        if (!demoMode) overviewSelection.clear()
        if (!demoMode && ::content.isInitialized) content.removeAllViews()
        locationMonitor?.stop()
        timer.removeCallbacks(feedPoll)
        timer.removeCallbacks(drivingWatch)
        super.onPause()
    }

    override fun onDestroy() {
        exportResultToast?.cancel()
        exportResultToast = null
        timer.removeCallbacksAndMessages(null)
        comparisonSpeech?.close()
        if (::voice.isInitialized) voice.close()
        uxMonitor?.close()
        if (::adapter.isInitialized) adapter.close(notifyGear = false)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun pollFeed() {
        if (!demoMode || !foreground || selected !in listOf(
                "홈",
                "도로"
            ) || feedLoading || busy || isDestroyed
        ) return
        feedLoading = true
        executor.execute {
            val received = try {
                JSONObject(client.request("/api/demo/approaching-hazards"))
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                feedLoading = false
                if (!isDestroyed) {
                    val changed = sharedFeed?.optJSONArray("warnings")
                        .toString() != received?.optJSONArray("warnings")
                        .toString() || sharingUnavailable != (received == null)
                    sharedFeed = received
                    sharingUnavailable = received == null
                    if (changed && opened == null && selected in listOf("홈", "도로")) render()
                }
            }
        }
    }

    private fun pollComparisons() {
        if (!demoMode || !foreground || comparisonLoading || busy || isDestroyed) return
        comparisonLoading = true
        val ticket = comparisonGeneration
        val started = android.os.SystemClock.elapsedRealtime()
        executor.execute {
            val received = runCatching { JSONArray(client.request("/api/events")) }.getOrNull()
            val eligible = received?.let { rows ->
                (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .filter {
                        it.optString("source") == "synthetic" && EvidenceScope.canNotifyComparison(
                            it
                        )
                    }
                    .mapNotNull { it.optString("id").takeIf { id -> id.isNotBlank() } }.toSet()
            }
            runOnUiThread {
                comparisonLoading = false
                if (!isDestroyed && foreground && ticket == comparisonGeneration) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val before = drivingViewKey(now)
                    if (eligible == null || now - started !in 0L..4999L) {
                        comparisonNotice.invalidate()
                        driverEventNotice.invalidate()
                    } else {
                        comparisonNotice.accept(eligible, now)
                        driverEventNotice.accept(checkNotNull(received), now)
                        events = checkNotNull(received)
                    }
                    updateComparisonSpeech()
                    if (before != drivingViewKey(now)) render()
                    // 일반 시스템 알림은 따로 소비 기록을 둔다
                    // 화면·영상 주행 제한은 앱 안 패널에만 적용하고 시스템 알림 전달과는 분리한다
                    systemEventNotices.receive(
                        received, started, android.os.SystemClock.elapsedRealtime(),
                        !DemoEventMonitorService.running && DriverEventNotification.available(this)
                    )?.let { event ->
                        DriverEventNotification.show(this, event)
                    }
                }
            }
        }
    }

    private fun text(value: String, size: Float = 20f, color: Int = ink, bold: Boolean = false) =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            typeface = PleosTypography.face(bold)
            setPadding(0, dp(5), 0, dp(5))
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun surface(color: Int = panel, radius: Float = 12f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.toInt()).toFloat()
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit) =
        PhoneAccountUi(this, light).button(label, primary) {
            if (!busy) action()
        }.apply {
            layoutParams =
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(13), 0, dp(13)) }
        }

    private fun card(
        parent: LinearLayout,
        title: String,
        description: String,
        accent: Int = ink
    ): LinearLayout {
        val box = column().apply {
            background = surface()
            setPadding(dp(24), dp(20), dp(24), dp(20))
            layoutParams =
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) }
        }
        box.addView(text(title, 24f, accent, true))
        box.addView(text(description, 18f, muted))
        parent.addView(box)
        return box
    }

    private var exportResultToast: Toast? = null
    private fun showPendingExportResult() {
        if (!foreground || driving || isDestroyed) {
            exportResultToast?.cancel()
            exportResultToast = null
            return
        }
        val preferences = getSharedPreferences("export_result", MODE_PRIVATE)
        val message = preferences.getString("pending", null) ?: return
        if (preferences.edit().remove("pending").commit()) {
            exportResultToast?.cancel()
            exportResultToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    private fun reportExportResult(message: String) {
        getSharedPreferences("export_result", MODE_PRIVATE).edit().putString("pending", message)
            .commit()
        showPendingExportResult()
    }

    private fun render() {
        if (selected != "차량" || driving || !demoMode) {
            voicePermissionRequested = false
            pendingVoicePermission = null
            voicePermissionDenied = false
            if (::voice.isInitialized) voice.close()
            voiceListening = false
            voiceMessage = ""
            voiceFallback = false
        }
        if (voicePermissionDenied && selected == "차량" && !driving) {
            voiceMessage = "마이크 권한 없음 · 버튼으로 진행할 수 있습니다"
            voiceFallback = true
        }
        if (pendingVoicePermission != null && voiceAllowed()) timer.post { consumeVoicePermission() }
        exportButton = null
        exportButtonEventId = null
        showPendingExportResult()
        driverBriefHost = null
        if (diagnosticReviewParent != null && (driving || !foreground || selected != diagnosticReviewSelection || opened?.optString(
                "id"
            ) != diagnosticReviewParent?.optString("id"))
        ) clearDiagnosticReview()
        if (driving) {
            if (::voice.isInitialized) voice.close()
            voiceListening = false
            voiceMessage = ""
            voiceFallback = false
            clearRoadSource()
            pendingOpenGeneration++
            detailReturnGeneration++
            detailReturnLoading = false
        }
        updateComparisonSpeech()
        if (!accountRecordsAllowed()) {
            accountRecords.clear()
            if (!demoMode) overviewSelection.clear()
        } else accountRecords.expire(
            System.currentTimeMillis(),
            android.os.SystemClock.elapsedRealtime()
        )
        activeMapPanel = null
        renderedParkingCue = parkingCueVisible()
        renderedDrivingLabel = drivingViewKey(android.os.SystemClock.elapsedRealtime())
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = column().apply { setBackgroundColor(bg) }
        val header = row().apply { setPadding(dp(22), 0, dp(22), 0) }
        header.addView(
            CockpitIcon(this, "shield", ink),
            LinearLayout.LayoutParams(cockpitDp(36), cockpitDp(36))
        )
        header.addView(
            text(getString(R.string.app_name), 22f * cockpitScale, ink, true),
            LinearLayout.LayoutParams(cockpitDp(126), -2)
        )
        status = text(
            if (driving) "" else if (busy) "처리 중…" else if (requestFailed) "자료를 불러오지 못했습니다" else if (comparisonNoticeVisible(
                    android.os.SystemClock.elapsedRealtime()
                )
            ) "시험 비교 자료 준비" else "", 18f * cockpitScale, muted
        )
        header.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        val mode = text(
            (if (light) "주간 화면" else "야간 화면") + if (displayMode == "system") " · 자동" else "",
            15f * cockpitScale,
            muted
        )
        if (selected == "설정" && !driving) header.addView(mode)
        root.addView(header, LinearLayout.LayoutParams(-1, cockpitDp(54)))
        if (driving) {
            val drivingViewport = FrameLayout(this)
            driverBriefHost = drivingViewport
            root.addView(drivingViewport, LinearLayout.LayoutParams(-1, 0, 1f))
            setContentView(root)
            DriverBriefPanel.render(
                PhoneAccountUi(this, light),
                drivingViewport,
                drivingState.briefState(android.os.SystemClock.elapsedRealtime()),
                comparisonNoticeVisible(android.os.SystemClock.elapsedRealtime()),
                driverEventVisible(android.os.SystemClock.elapsedRealtime())
            )
            return
        }
        val body = row().apply {
            gravity = Gravity.TOP
            setPadding(dp(12), 0, dp(14), dp(12))
        }
        val nav = row().apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
            setBackgroundColor(panel)
        }
        for (name in listOf("기록", "차량", "설정")) {
            val active = selected == name
            val item = column().apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = when (name) {
                    "기록" -> "사건 근거"
                    "차량" -> "정비 비교"
                    else -> name
                }
                val fill = if (active) (if (light) c("#EAF1FC") else c("#25344D")) else bg
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_focused),
                        surface(fill, 12f).apply { setStroke(cockpitDp(2), blue) })
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        surface(fill, 12f).apply { setStroke(cockpitDp(2), blue) })
                    addState(intArrayOf(), surface(fill, 12f))
                }
                setPadding(cockpitDp(4), cockpitDp(10), cockpitDp(4), cockpitDp(10))
                minimumHeight = cockpitDp(100)
                addView(
                    CockpitIcon(
                        this@MainActivity,
                        name,
                        ink
                    ).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    LinearLayout.LayoutParams(cockpitDp(76), cockpitDp(76))
                )
                addView(
                    text(
                        when (name) {
                            "기록" -> "사건 근거"
                            "차량" -> "정비 비교"
                            else -> name
                        }, 20f * cockpitScale, if (active) blue else muted, active
                    ).apply {
                        gravity = Gravity.CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                setOnClickListener {
                    if (!busy) {
                        clearRoadSource()
                        detailReturnId = null
                        detailReturnGeneration++
                        selected = name
                        opened = null
                        helpRequested = false
                        render()
                    }
                }
            }
            nav.addView(
                item,
                LinearLayout.LayoutParams(cockpitDp(108), -2).apply { marginEnd = cockpitDp(24) })
        }

        val viewport = FrameLayout(this).apply { setBackgroundColor(panel) }
        body.addView(viewport, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(-1, cockpitDp(140)))
        setContentView(root)
        if (helpRequested) {
            emergencyScreen(viewport)
            return
        }
        if (opened?.getJSONObject("assessment")?.getString("kind") in listOf(
                "manual_help",
                "emergency"
            )
        ) {
            emergencyScreen(viewport, opened)
            return
        }
        if (demoMode && opened == null && selected in listOf("홈", "도로")) {
            mapScreen(viewport)
            return
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = column().apply { setPadding(dp(28), dp(20), dp(28), dp(24)) }
        scroll.addView(content)
        viewport.addView(scroll)
        val unread = pendingComparisonCount(android.os.SystemClock.elapsedRealtime())
        if (detailReturnId == null && opened == null && selected in setOf("기록", "차량") && unread > 0)
            content.addView(
                PhoneAccountUi(
                    this,
                    light
                ).button("새 비교 자료 ${unread}건 · 열기") { openPendingComparison() })
        when {
            detailReturnId != null -> {
                val ui = PhoneAccountUi(this, light)
                content.addView(ui.notice(if (detailReturnLoading) "비교 자료를 다시 확인하고 있습니다" else if (detailReturnFailed) "비교 자료를 확인하지 못했습니다" else "주차 후 비교 자료를 다시 확인하세요"))
                if (!detailReturnLoading) content.addView(ui.button("다시 확인") { refreshReturnedDetail() })
                content.addView(ui.button("사건 근거 목록") {
                    detailReturnId = null
                    detailReturnGeneration++
                    opened = null
                    selected = "기록"
                    render()
                })
            }

            opened != null && !driving -> eventDetail(opened!!)
            demoMode && selected == "집계" -> if (driving) locked() else {
                val ui = PhoneAccountUi(this, light)
                val page = roadGroupPage
                if (page != null) RoadGroupPanel.render(
                    ui, content, page,
                    open = { id -> openRoadGroupEvent(id) },
                    next = { offset ->
                        openRoadGroup(
                            page.getString("group_id"),
                            offset,
                            page.getString("membership_sha256")
                        )
                    },
                    back = {
                        roadGroupPage = null
                        render()
                    }, client = client, allowed = { foreground && !driving })
                else roadSummary?.let {
                    RoadSummaryPanel.render(
                        ui,
                        content,
                        it,
                        client = client,
                        allowed = { foreground && !driving }) { id -> openRoadGroup(id, 0) }
                } ?: content.addView(ui.notice("집계 자료를 다시 불러오세요"))
                val summaryActions =
                    LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                summaryActions.addView(ui.button("집계 새로 확인") {
                    task {
                        roadGroupPage = null
                        roadSummary = JSONObject(client.request("/api/demo/road-summary"))
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
                summaryActions.addView(ui.button("사건 근거 목록") {
                    selected = "기록"
                    render()
                }, LinearLayout.LayoutParams(0, -2, 1f))
                content.addView(summaryActions)
            }

            selected == "설정" -> settings()
            selected == "기록" -> if (driving) locked() else history()
            selected == "차량" -> vehicle()
            else -> locked()
        }
    }

    private fun sharedRoadFeedCurrent(): Boolean =
        !sharingUnavailable && sharedFeed?.optString("mode") == "synthetic-only" &&
                RoadDisplay.feedCurrent(
                    sharedFeed?.optDouble("generated_at", Double.NaN) ?: Double.NaN,
                    System.currentTimeMillis() / 1000.0
                )

    private fun activeSharedRoad(): JSONObject? {
        if (!sharedRoadFeedCurrent()) return null
        val warnings = sharedFeed?.optJSONArray("warnings")
        return (0 until (warnings?.length() ?: 0)).map { warnings!!.getJSONObject(it) }
            .firstOrNull {
                RoadDisplay.current(
                    it.optDouble("latest_at", Double.NaN),
                    it.optDouble("expires_at", Double.NaN),
                    System.currentTimeMillis() / 1000.0,
                    true,
                    "shared"
                )
            }
    }

    private fun activeLocalRoad(): JSONObject? {
        val now = System.currentTimeMillis() / 1000.0
        return (0 until events.length()).map { events.getJSONObject(it) }.firstOrNull { event ->
            event.getJSONObject("assessment").getString("kind") in listOf("pothole", "rain") &&
                    RoadDisplay.current(
                        event.optDouble("created", Double.NaN),
                        event.optDouble("road_expires_at", Double.NaN),
                        now,
                        event.optBoolean("evidence_valid", false),
                        event.getString("status")
                    )
        }
    }

    private fun roadKey(): String =
        activeSharedRoad()?.let { "shared:" + it.getString("id") + ":" + ((it.optDouble("expires_at") - System.currentTimeMillis() / 1000.0) / 60).toLong() }
            ?: activeLocalRoad()?.let { "local:" + it.getString("id") } ?: "none"

    private fun mapScreen(parent: FrameLayout) {
        val fix = locationMonitor?.current()
        renderedLocationKey = locationKey()
        val shared = if (parkingPreview || fix != null || !demoMode) null else activeSharedRoad()
        val roadEvent = if (parkingPreview || fix != null || !demoMode) null else activeLocalRoad()
        renderedRoadKey = roadKey()
        val hazard = shared != null || roadEvent != null
        val eventLocation = shared?.getJSONObject("location") ?: roadEvent?.getJSONObject("data")
            ?.getJSONObject("location")
        val position = eventLocation?.let {
            org.maplibre.android.geometry.LatLng(
                it.getDouble("lat"),
                it.getDouble("lon")
            )
        }
        val receiver = sharedFeed?.optJSONObject("vehicle")
        val vehiclePosition = receiver?.let {
            org.maplibre.android.geometry.LatLng(
                it.getDouble("lat"),
                it.getDouble("lon")
            )
        } ?: org.maplibre.android.geometry.LatLng(37.5, 126.997)
        val stacked = resources.configuration.screenWidthDp < 700
        val layout = LinearLayout(this).apply {
            orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        parent.addView(layout, FrameLayout.LayoutParams(-1, -1))
        val mapArea = FrameLayout(this)
        layout.addView(
            mapArea,
            if (stacked) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(
                0,
                -1,
                1f
            )
        )
        if (fix != null) mapArea.addView(
            NativeMapPanel(
                this, light, null, driving,
                org.maplibre.android.geometry.LatLng(fix.latitude, fix.longitude),
                "${if (fix.mock) "시험 GPS" else "기기 GPS"} · 정확도 약 ${fix.accuracy.toInt()} m", false
            ).also { activeMapPanel = it }, FrameLayout.LayoutParams(-1, -1)
        )
        else if (demoMode && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            mapArea.addView(
                NativeMapPanel(this, light, position, driving, vehiclePosition),
                FrameLayout.LayoutParams(-1, -1)
            )
        else mapArea.addView(
            text(
                "현재 위치를 확인하지 못했습니다.\n주차 후 설정에서 위치 권한과 기기 위치 서비스를 확인하세요.",
                23f,
                muted
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(70), dp(24), dp(24))
            }, FrameLayout.LayoutParams(-1, -1)
        )
        val mapTitle = text("내 주변", 23f, ink, true).apply {
            background = surface()
            setPadding(dp(22), dp(10), dp(22), dp(10))
        }
        mapArea.addView(
            mapTitle,
            FrameLayout.LayoutParams(-2, dp(60), Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(18), dp(18), 0, 0) })
        val narrow = resources.configuration.screenWidthDp < 850
        val side = column().apply {
            setBackgroundColor(panel)
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        val panelWidth = if (narrow) dp(290) else dp(360)
        if (!driving) {
            val sideScroll = ScrollView(this).apply {
                isFillViewport = true
                addView(side)
            }
            layout.addView(
                sideScroll,
                if (stacked) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(
                    panelWidth,
                    -1
                )
            )
        } else layout.addView(
            side,
            if (stacked) LinearLayout.LayoutParams(
                -1,
                -2
            ) else LinearLayout.LayoutParams(panelWidth, -1)
        )
        if (driving) {
            side.addView(
                CockpitIcon(
                    this,
                    if (hazard) "alert" else "shield",
                    if (hazard) amber else blue
                ), LinearLayout.LayoutParams(dp(48), dp(48))
            )
            val warning = shared?.optString("message") ?: roadEvent?.getJSONObject("assessment")
                ?.optString("title")
            side.addView(
                text(
                    warning
                        ?: if (!demoMode) "도로 위험 정보\n서비스 연결 전" else if (!sharedRoadFeedCurrent()) "도로 정보\n연결 확인 필요" else "공유된 위험 없음",
                    30f,
                    ink,
                    true
                ).apply {
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            if (shared != null) {
                side.addView(text("약 ${shared.getInt("distance_m")} m 앞", 23f, amber, true))
                side.addView(
                    text(
                        shared.optJSONObject("advisory")?.optString("instruction")
                            ?: "전방 도로 상태를 확인하세요.", 20f, ink
                    )
                )
            }
            side.addView(
                text(
                    if (demoMode) "도로 정보" else if (fix != null) "현재 위치만 표시합니다" else "현재 위치도 확인이 필요합니다",
                    17f,
                    muted
                )
            )
            if (!stacked) side.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
            side.addView(text("기록과 사진은 주차 후 확인하세요", 18f, muted))
            return
        }
        renderedParkingCue = parkingCueVisible()
        val parkingPrompt = renderedParkingCue && !hazard
        if (parkingPrompt) {
            side.addView(
                CockpitIcon(this, "차량", blue),
                LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(20) })
            side.addView(text("주차 위치를\n기억해 둘까요?", 32f, ink, true))
            side.addView(text("층과 기둥 번호를\n휴대전화 사진으로 남겨 주세요.", 21f, muted).apply {
                setPadding(
                    0,
                    dp(20),
                    0,
                    dp(20)
                )
            })
            side.addView(text("휴대전화 R 앱에서\n‘주차 사진 저장하기’를 선택하세요.", 18f, muted))
            if (demoMode && getSharedPreferences(
                    "parking_arrival",
                    MODE_PRIVATE
                ).contains("relay_pending")
            )
                side.addView(text("개발 연결 대기 · 사진은 휴대전화에서 바로 저장할 수 있습니다.", 16f, amber))
            if (parkingPreview) side.addView(text("가상 주차 안내 미리보기", 15f, amber))
        } else {
            if (shared != null) RoadWarningPanel.render(
                this,
                side,
                shared,
                light,
                System.currentTimeMillis() / 1000.0
            )
            else {
                val badge = row()
                badge.addView(
                    CockpitIcon(
                        this,
                        if (hazard) "alert" else "shield",
                        if (hazard) amber else blue
                    ), LinearLayout.LayoutParams(dp(36), dp(36))
                )
                badge.addView(
                    text(
                        if (shared != null) "접근 중인 위험" else if (hazard) "도로 관측 기록" else "도로 상황",
                        17f,
                        if (hazard) amber else blue,
                        true
                    )
                )
                side.addView(badge)
                val heading = shared?.getString("message") ?: if (hazard) roadEvent!!.getJSONObject(
                    "assessment"
                )
                    .getString("title") else if (!demoMode) "도로 위험 정보\n서비스 연결 전" else if (!sharedRoadFeedCurrent()) "위험 정보\n확인 불가" else "공유된 위험\n없음"
                side.addView(text(heading, 32f, ink, true))
                side.addView(
                    text(
                        if (shared != null) "약 ${shared.getInt("distance_m")} m 앞 · ${
                            shared.getInt(
                                "eta_seconds"
                            )
                        }초" else if (hazard) stateLabel(roadEvent!!.getString("status")) else if (!demoMode) "위험 유무를 판단할 수 없습니다" else if (!sharedRoadFeedCurrent()) "연결 상태를 확인해 주세요" else "현재 수신한 정보 기준",
                        17f,
                        muted
                    )
                )
                if (hazard) side.addView(
                    text(
                        if (shared != null) "가상 차량 ${shared.getInt("independent_vehicle_count")}대 관측" else "지도에서 기록 위치를 확인하세요.",
                        20f,
                        ink
                    )
                )
            }
            if (sharingUnavailable) side.addView(text("공유 연결 끊김 · 내 기록만 표시", 16f, amber))
            val divider =
                View(this).apply { setBackgroundColor(if (light) c("#DFE5EB") else c("#3B4A58")) }
            side.addView(
                divider,
                LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(18), 0, dp(14)) })
            if (!hazard) {
                side.addView(text("내 차량", 16f, muted))
                side.addView(text(if (demoMode) "아이오닉 6" else "차량 연결", 23f, ink, true))
                side.addView(
                    text(
                        if (demoMode) "차량 상태 점검" else drivingState.label(android.os.SystemClock.elapsedRealtime()),
                        16f,
                        muted
                    )
                )
            }
        }
        if (!stacked) side.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val action = button(
            if (parkingPrompt) "주차 안내 확인" else if (shared != null) "공유 위험 새로 확인" else if (hazard && !driving) "위험 기록 확인" else if (demoMode) "차량 진단" else "차량 연결 확인",
            true
        ) {
            if (parkingPrompt) {
                parkingPreview = false
                getSharedPreferences("parking_arrival", MODE_PRIVATE).edit().remove("pending_at")
                    .apply()
                render()
            } else if (shared != null) pollFeed() else if (hazard && !driving) {
                opened = roadEvent
                render()
            } else if (demoMode) runScenario("diagnosis") else {
                selected = "차량"
                render()
            }
        }
        side.addView(action, LinearLayout.LayoutParams(-1, dp(80)).apply { topMargin = dp(10) })
        side.addView(button("긴급 도움 · 119 / 112") {
            helpRequested = true
            render()
        }, LinearLayout.LayoutParams(-1, dp(80)).apply { topMargin = dp(16) })
    }

    private fun emergencyScreen(parent: FrameLayout, event: JSONObject? = null) {
        val body = column().apply { setPadding(dp(40), dp(28), dp(40), dp(24)) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }
        parent.addView(scroll)
        if (event == null) body.addView(text("어떤 도움이 필요한가요?", 32f, ink, true))
        if (event == null && demoMode) {
            val ui = PhoneAccountUi(this, light)
            val target = helpTarget
            if (target == null) {
                for (number in listOf(
                    "119",
                    "112"
                )) body.addView(ui.button(if (number == "119") "119 구조·구급" else "112 경찰") {
                    if (!driving && !busy) {
                        helpTarget = number
                        render()
                    }
                }.apply { isEnabled = !driving && !busy })
                body.addView(ui.button("돌아가기") {
                    helpRequested = false
                    helpTarget = null
                    render()
                })
            } else {
                val pending = getSharedPreferences("pending_emergency", MODE_PRIVATE)
                val uncertain = requestFailed && pending.getString(
                    "target",
                    null
                ) == target && !pending.getString("key", null).isNullOrBlank()
                body.addView(
                    ui.text(
                        if (uncertain) "접수 결과 확인 필요" else "$target 모의 요청을 보낼까요?",
                        30f,
                        true
                    )
                )
                if (uncertain) body.addView(
                    DriverEventGraphic(this, "timeline_waiting", light),
                    LinearLayout.LayoutParams(-1, dp(150))
                )
                body.addView(ui.button(if (uncertain) "같은 요청 다시 확인" else "모의 요청 보내기") {
                    if (!driving && !busy) requestHelp(target)
                }.apply { isEnabled = !driving && !busy })
                body.addView(ui.button(if (uncertain) "닫기" else "취소") {
                    if (!busy) {
                        helpTarget = null
                        render()
                    }
                }.apply { isEnabled = !busy })
            }
            return
        }
        if (event == null) {
            body.addView(text("전화 앱에서 통화를 시작하세요. 연결 여부는 전화 앱에서 확인할 수 있습니다.", 21f, muted))
            val dialStatus = text("기관 자동 접수·위치 및 영상 전송·보험사 접수는 연결되지 않았습니다.", 20f, muted)
            val choices = row()
            fun contact(number: String, label: String) = button(label, true) {
                if (!driving) dialStatus.text =
                    if (EmergencyContact.open(this, number)) "전화 앱으로 이동했습니다. 연결 여부를 확인하세요."
                    else "이 차량에서 전화 앱을 열 수 없습니다. 휴대전화로 ${number}에 연락하세요."
            }.apply { isEnabled = !driving }

            val rescue = contact("119", "119  구조·구급")
            val police = contact("112", "112  경찰")
            if (resources.configuration.screenWidthDp < 850) {
                body.addView(rescue)
                body.addView(police)
            } else {
                choices.addView(
                    rescue,
                    LinearLayout.LayoutParams(0, dp(110), 1f)
                        .apply { setMargins(0, dp(32), dp(16), dp(24)) })
                choices.addView(
                    police,
                    LinearLayout.LayoutParams(0, dp(110), 1f)
                        .apply { setMargins(dp(16), dp(32), 0, dp(24)) })
                body.addView(choices)
            }
            body.addView(dialStatus)
            body.addView(text("통화 시 현재 위치·진행 방향, 사고 상황·다친 사람 수, 차량 번호·차종·색상을 알려 주세요.", 21f, muted))
            if (demoMode) {
                body.addView(text("개발 시험 · 실제 기관에 접수되지 않습니다", 19f, amber))
                body.addView(button("119 모의 요청") { requestHelp("119") })
                body.addView(button("112 모의 요청") { requestHelp("112") })
            }
            body.addView(button("돌아가기") {
                helpRequested = false
                render()
            })
        } else {
            val assessment = event.getJSONObject("assessment")
            val deliveries = event.getJSONArray("dispatches")
            val expected = assessment.optString("recipient")
            val delivery = (0 until deliveries.length()).map { deliveries.getJSONObject(it) }
                .firstOrNull { it.optString("recipient") == expected }
            val receipt =
                if (delivery == null || delivery.isNull("receipt")) null else delivery.optString("receipt")
            val state = EmergencyDelivery.state(
                expected,
                delivery?.optString("recipient"),
                delivery?.optString("status"),
                receipt,
                event.optBoolean("evidence_valid", false)
            )
            val ui = PhoneAccountUi(this, light)
            val witness = demoMode && WitnessCasePanel.render(ui, body, event, client, allowed = {
                foreground && !driving && !busy && opened?.optString("id") == event.optString("id")
            }, onRequest = { dispatch(event, "119-mock") }, onReturn = {
                opened = null
                helpRequested = false
                selected = "기록"
                refresh()
            })
            if (!witness) EventVisualBrief.render(ui, body, event, client, allowed = {
                foreground && !driving && opened?.optString("id") == event.optString("id")
            })
            if (demoMode && !driving) HelpVideoConnectionPanel.render(
                ui, body, event,
                allowed = {
                    foreground && !driving && !busy && opened?.optString("id") == event.optString(
                        "id"
                    )
                },
                onConnect = { connectHelpVideo(event) },
                onOpen = {
                    HelpVideoConnectionPanel.session(event)?.let {
                        openComparisonVideo(
                            event.getString("id"),
                            event.getString("id"),
                            it.getString("session_id")
                        )
                    }
                })
            val details = column()
            val scene = event.optJSONObject("video")
            if (demoMode && scene?.optString("source") == "synthetic_scene_fixture" && scene.optBoolean(
                    "integrity_valid"
                )
            )
                details.addView(ui.button("영상 보기") {
                    openComparisonVideo(
                        event.getString("id"),
                        event.getString("id")
                    )
                })
            EmergencyCasePanel.render(ui, details, event)
            if (!driving) ui.disclosure(body, "상세 기록", details)
            if (!driving && state == "retry") body.addView(ui.button("같은 요청 다시 전달") { dispatch(event) })
            if (witness && EventBriefState.from(event).next != "접수 완료") body.addView(ui.textButton("기록 목록으로") {
                if (!busy) {
                    opened = null
                    helpRequested = false
                    selected = "기록"
                    refresh()
                }
            }) else if (!witness) body.addView(ui.button("주행 화면으로") {
                opened = null
                helpRequested = false
                selected = "홈"
                render()
            })
        }
    }

    private fun connectHelpVideo(event: JSONObject) {
        if (!demoMode || !foreground || driving || busy || opened?.optString("id") != event.optString(
                "id"
            ) || !HelpVideoConnectionPanel.eligible(event)
        ) return
        val id = event.getString("id")
        val hash = event.getString("hash")
        busy = true
        render()
        executor.execute {
            val result = runCatching {
                client.request(
                    "/api/events/$id/help-video-session",
                    JSONObject().put("original_hash", hash)
                )
                JSONObject(client.request("/api/events/$id")).also {
                    require(
                        it.optString("hash") == hash && HelpVideoConnectionPanel.session(
                            it
                        ) != null
                    )
                }
            }
            runOnUiThread {
                busy = false
                if (isDestroyed || isFinishing || !foreground || driving || opened?.optString("id") != id || opened?.optString(
                        "hash"
                    ) != hash
                ) return@runOnUiThread
                result.fold(onSuccess = { current ->
                    opened = current
                    openComparisonVideo(
                        id,
                        id,
                        HelpVideoConnectionPanel.session(current)?.getString("session_id")
                    )
                }, onFailure = {
                    requestFailed = true
                    render()
                })
            }
        }
    }

    private fun requestHelp(target: String) {
        if (!demoMode || !foreground || driving || busy || target !in setOf("119", "112")) return
        task {
            val preferences = getSharedPreferences("pending_emergency", MODE_PRIVATE)
            val key = if (preferences.getString("target", null) == target) preferences.getString(
                "key",
                null
            ) ?: UUID.randomUUID().toString() else UUID.randomUUID().toString()
            check(
                preferences.edit().putString("target", target).putString("key", key).commit()
            ) { "요청 기록을 저장하지 못했습니다" }
            val event = JSONObject(
                client.request(
                    "/api/emergency/request",
                    JSONObject().put("target", target).put("request_key", key)
                )
            )
            opened = event
            helpRequested = false
            helpTarget = null
            preferences.edit().remove("target").remove("key").commit()
            try {
                events = JSONArray(client.request("/api/events"))
            } catch (_: Exception) {/* Keep the received acknowledgement visible. */
            }
        }
    }

    /** 아직 보이는 기록에만 적용한다. 화면을 떠난 동작이 운전자를 다른 화면으로 옮기지 않게 한다 */
    private fun onDemoActionEventChanged(expected: JSONObject, current: JSONObject) {
        if (!demoMode || !foreground || driving || isFinishing || isDestroyed) return
        if (current.optString("id") != expected.optString("id") || current.optString("hash") != expected.optString(
                "hash"
            ) || current.optString("source") != "synthetic"
        ) return
        val detailVisible = opened === expected
        val vehicleVisible = opened == null && selected == "차량" && (0 until events.length()).any {
            events.optJSONObject(it) === expected
        }
        if (!detailVisible && !vehicleVisible) return
        for (i in 0 until events.length()) if (events.optJSONObject(i)
                ?.optString("id") == expected.optString("id")
        ) events.put(i, current)
        if (detailVisible) opened = current
        render()
    }

    private fun vehicle() {
        if (driving) {
            locked()
            return
        }
        if (!demoMode) content.addView(text("정비 전후 비교", 32f, ink, true))
        val ui = PhoneAccountUi(this, light)
        if (!demoMode) {
            if (!accountRecords.requested || accountRecords.category != "maintenance") {
                loadAccountRecords(null, category = "maintenance")
                return
            }
            if (accountRecords.loading) {
                content.addView(ui.notice("내 차량의 측정 기록을 확인하고 있습니다."))
                return
            }
            accountRecords.error?.let { message ->
                content.addView(ui.notice(message))
                content.addView(button("연결 상태 확인") {
                    selected = "설정"
                    render()
                })
                content.addView(button("측정 기록 다시 확인", true) { loadAccountRecords(null) })
                return
            }
            accountRecords.detail?.let { event ->
                if (event.optJSONObject("assessment")?.optString("kind") == "diagnosis") {
                    VehicleEventDetail.render(
                        this,
                        content,
                        event,
                        light,
                        client = if (demoMode) client else null,
                        allowed = { foreground && !driving },
                        onOpenDiagnostic = { onOpenDiagnosticReview(event) })
                    content.addView(button("원본과 사건 상세", true) {
                        selected = "기록"
                        render()
                    })
                } else if (event.optString("source") == "owner_reported" && EvidenceScope.category(
                        event
                    ) == "maintenance"
                ) {
                    VehicleEventDetail.render(
                        this,
                        content,
                        event,
                        light,
                        client = if (demoMode) client else null,
                        allowed = { foreground && !driving },
                        onOpenDiagnostic = { onOpenDiagnosticReview(event) })
                    privateCaseAttachments(ui, event)
                    content.addView(text("사진 추가와 자료 저장은 휴대전화에서 확인하세요.", 18f, muted))
                } else content.addView(ui.notice("이 기록은 정비 측정 자료가 아닙니다."))
                content.addView(button("비교 기록 목록") { loadAccountRecords(null) })
                return
            }
            val page = accountRecords.page
            MaintenanceRecordList.render(
                ui,
                content,
                page?.events ?: JSONArray()
            ) { loadAccountRecords(id = it) }
            page?.next?.let { next ->
                content.addView(button("이전 시기의 측정 기록 찾기") {
                    loadAccountRecords(
                        next
                    )
                })
            }
            if (accountRecords.cursor != null) content.addView(button("최근 기록으로") {
                loadAccountRecords(
                    null
                )
            })
            return
        }
        val measured = (0 until events.length()).map { events.getJSONObject(it) }
            .filter { it.optJSONObject("assessment")?.optString("kind") == "diagnosis" }
        val empty = measured.isEmpty()
        val entry = if (empty) column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(28), 0, dp(24))
            addView(
                CockpitIcon(this@MainActivity, "차량", ink),
                LinearLayout.LayoutParams(dp(160), dp(160)).apply { bottomMargin = dp(24) })
            addView(ui.text("차량 진단", 32f, true).apply { gravity = Gravity.CENTER })
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        } else content.apply { addView(ui.text("정비 기록", 32f, true)) }
        val voiceAction: () -> Unit = {
            if (voiceListening) {
                voice.close()
                voiceListening = false
                voiceMessage = "음성 입력 취소"
                voiceFallback = false
                render()
            } else startVoice()
        }
        val voiceLabel = if (voiceListening) "음성 입력 취소" else "음성으로 진단 요청"
        entry.addView(
            (if (empty) ui.button(voiceLabel, true, voiceAction) else ui.textButton(
                voiceLabel,
                voiceAction
            )).apply { isEnabled = !busy })
        if (voiceMessage.isNotEmpty()) entry.addView(
            ui.text(voiceMessage, 22f)
                .apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        if (empty || voiceFallback) entry.addView(ui.textButton("차량 진단") {
            if (voiceAllowed()) runScenario(
                "diagnosis"
            )
        }.apply { isEnabled = !busy })
        if (measured.isNotEmpty()) {
            val latest = measured.first()
            VehicleEventDetail.render(
                this,
                content,
                latest,
                light,
                client = if (demoMode) client else null,
                allowed = { foreground && !driving },
                onOpenDiagnostic = { onOpenDiagnosticReview(latest) },
                onEventChanged = { onDemoActionEventChanged(latest, it) })
            content.addView(button("이 기록의 원본과 작업 이력", true) {
                opened = latest
                render()
            })
            MaintenanceRecordList.render(ui, content, events) { id ->
                task {
                    opened = JSONObject(client.request("/api/events/$id"))
                }
            }
        }
    }

    private fun history() {
        if (!demoMode) {
            accountHistory()
            return
        }
        workspaceHeading { refresh() }
        if (events.length() == 0 && (busy || requestFailed)) {
            content.addView(
                text(
                    if (busy) "기록 확인 중" else "기록을 불러오지 못했습니다",
                    28f * cockpitScale,
                    ink,
                    true
                )
            )
            if (requestFailed) content.addView(
                text(
                    "새로 확인을 눌러 다시 연결해 주세요",
                    20f * cockpitScale,
                    muted
                )
            )
            return
        }
        if (requestFailed) content.addView(
            text(
                "마지막으로 받은 기록입니다 · 새로 확인해 주세요",
                20f * cockpitScale,
                muted
            )
        )
        CaseWorkspace.render(
            PhoneAccountUi(this, light),
            content,
            events,
            selection = overviewSelection
        ) { id ->
            task { opened = JSONObject(client.request("/api/events/$id")) }
        }
    }

    private fun workspaceHeading(refreshAction: () -> Unit) {
        val heading = row()
        heading.addView(
            text("사건 근거", 30f * cockpitScale, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        heading.addView(
            PhoneAccountUi(
                this,
                light
            ).compactButton("새로 확인") { if (!busy) refreshAction() }.apply {
                textSize = 18f * cockpitScale
                minHeight = cockpitDp(48)
                minimumHeight = cockpitDp(48)
                isEnabled = !accountRecords.loading
            }, LinearLayout.LayoutParams(cockpitDp(142), -2).apply { bottomMargin = cockpitDp(8) })
        content.addView(heading)
    }

    private fun loadAccountRecords(
        cursor: String? = accountRecords.cursor,
        id: String? = null,
        category: String = accountRecords.category
    ) {
        if (!accountRecordsAllowed()) return
        val ticket = accountRecords.begin(cursor, category)
        render()
        executor.execute {
            val result = runCatching {
                val auth = AuthenticatedEventsClient(this)
                if (id == null) auth.pageForDisplay(cursor, category)
                    .let { Triple(it.value, null, it.lease) }
                else auth.eventForDisplay(id).let { Triple(null, it.value, it.lease) }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val failure = result.exceptionOrNull()
                val message =
                    if (failure == null) null else (failure as? AccountAccessException)?.message
                        ?: "지금은 차량 기록을 받을 수 없습니다. 잠시 후 다시 확인해 주세요."
                if (accountRecords.accept(
                        ticket,
                        accountRecordsAllowed(),
                        result.getOrNull()?.first,
                        result.getOrNull()?.second,
                        message,
                        result.getOrNull()?.third,
                        (failure as? AccountAccessException)?.reason
                    )
                ) render()
            }
        }
    }

    private fun privateCaseAttachments(ui: PhoneAccountUi, detail: JSONObject) {
        if (BuildConfig.MEASUREMENTS_ENABLED && detail.optString("source") == "owner_reported" && detail.optBoolean(
                "evidence_valid"
            ) && EvidenceScope.category(detail) == "maintenance"
        )
            content.addView(
                VehicleMeasurementsPanel(
                    this,
                    ui,
                    detail.getString("id"),
                    detail.getString("hash")
                ) { accountRecordsAllowed() })
        if (BuildConfig.CASE_PHOTOS_ENABLED && detail.optString("source") == "owner_reported" && detail.optBoolean(
                "evidence_valid"
            )
        )
            content.addView(
                CasePhotosPanel(
                    this,
                    ui,
                    detail.getString("id")
                ) { accountRecordsAllowed() })
        if (BuildConfig.SDK_OBSERVATIONS_ENABLED && detail.optBoolean("evidence_valid") && EvidenceScope.category(
                detail
            ) != null
        )
            content.addView(
                SdkObservationsPanel(
                    this,
                    ui,
                    detail.getString("id"),
                    detail.optString("source") == "synthetic",
                    { accountRecordsAllowed() },
                    { adapter.diagnosticSnapshot() })
            )
    }

    private fun accountHistory() {
        if (!accountRecordsAllowed()) return
        if (!accountRecords.requested) {
            loadAccountRecords()
            return
        }
        val ui = PhoneAccountUi(this, light)
        val detail = accountRecords.detail
        if (detail != null) {
            VehicleEventDetail.render(
                this,
                content,
                detail,
                light,
                onOpenDiagnostic = { onOpenDiagnosticReview(detail) })
            privateCaseAttachments(ui, detail)
            content.addView(button("사건 목록으로", true) { loadAccountRecords() })
            content.addView(text("영상 재생과 자료 저장은 휴대전화에서 확인하세요.", 18f, muted))
            return
        }
        workspaceHeading {
            accountRecords.previous.clear()
            loadAccountRecords(null)
        }
        if (accountRecords.loading) {
            card(content, "기록 확인 중", "차량 접근 권한을 확인하고 있습니다.")
            return
        }
        accountRecords.error?.let { message ->
            val unavailable =
                accountRecords.errorReason == AccountAccessException.Reason.NOT_CONFIGURED
            val status = column().apply {
                setPadding(dp(36), dp(36), dp(36), dp(32))
                background = surface(if (light) c("#F5F5F5") else c("#242424"), 16f)
            }
            status.addView(
                text(
                    if (unavailable) "차량 기록 서비스 준비 중" else "기록을 확인하지 못했습니다",
                    38f,
                    ink,
                    true
                )
            )
            status.addView(
                text(
                    if (unavailable) "서비스가 연결되면 이곳에서 내 차량의 사건 기록을 확인할 수 있습니다." else message,
                    26f,
                    muted
                ).apply {
                    maxWidth = dp(980)
                    setLineSpacing(dp(7).toFloat(), 1f)
                })
            val action = button(if (unavailable) "차량 연결 상태" else "기록 다시 확인", true) {
                if (unavailable) {
                    selected = "차량"
                    render()
                } else {
                    accountRecords.previous.clear()
                    loadAccountRecords(null)
                }
            }.apply { textSize = 24f }
            status.addView(
                action,
                LinearLayout.LayoutParams(dp(340), dp(88)).apply { topMargin = dp(28) })
            content.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(32)
                bottomMargin = dp(36)
            })
            content.addView(text("주차 사진은 휴대전화에서", 26f, ink, true))
            content.addView(text("사진을 저장한 휴대전화의 ‘내 차 주차 위치 찾기’에서 확인하세요.", 23f, muted))
            return
        }
        accountRecords.page?.let { page ->
            CaseWorkspace.render(ui, content, page.events, accountRecords.category, {
                accountRecords.previous.clear()
                loadAccountRecords(null, category = it)
            }, selection = overviewSelection) { loadAccountRecords(id = it) }
            page.next?.let { next ->
                content.addView(button("이전 시기의 기록 더 보기", true) {
                    accountRecords.previous.add(accountRecords.cursor)
                    loadAccountRecords(next)
                })
            }
        }
        if (accountRecords.previous.isNotEmpty()) content.addView(button("앞 목록으로") {
            loadAccountRecords(accountRecords.previous.removeAt(accountRecords.previous.lastIndex))
        })
        content.addView(button("최신 기록부터 확인") {
            accountRecords.previous.clear()
            loadAccountRecords(null)
        })
    }

    private fun eventDetail(event: JSONObject) {
        if (diagnosticReviewParent?.optString("id") == event.optString("id")) {
            val ui = PhoneAccountUi(this, light)
            diagnosticReview?.let { DiagnosticEvidenceReview.render(ui, content, it) }
                ?: content.addView(
                    ui.notice(
                        if (diagnosticReviewFailed) "재측정 근거를 확인하지 못했습니다" else "재측정 근거를 확인하고 있습니다"
                    )
                )
            if (diagnosticReviewFailed) content.addView(ui.button("다시 확인") {
                diagnosticReviewParent?.let {
                    onOpenDiagnosticReview(
                        it
                    )
                }
            })
            content.addView(ui.button("요약으로 돌아가기") { returnFromDiagnosticReview() })
            return
        }
        if (demoMode && foreground && !driving && event.optString("source") == "synthetic" && EvidenceScope.canNotifyComparison(
                event
            )
        )
            comparisonNotice.acknowledge(event.optString("id"))
        if (roadSourceParent?.optString("id") == event.optString("id")) {
            val ui = PhoneAccountUi(this, light)
            roadSource?.let { RoadObservationSource.render(ui, content, it) }
                ?: content.addView(ui.notice(if (roadSourceFailed) "연결된 원본을 확인하지 못했습니다" else "원본을 확인하고 있습니다"))
            roadSource?.let { source ->
                val video = source.optJSONObject("video")
                if (video?.optString("source") == "synthetic_scene_fixture" && video.opt("integrity_valid") == true)
                    content.addView(ui.button("이후 관측 참고 영상") {
                        openComparisonVideo(
                            source.getString(
                                "id"
                            ), event.getString("id")
                        )
                    })
            }
            content.addView(ui.button("비교 결과로 돌아가기") { returnFromRoadSource() })
            return
        }
        if (demoMode && event.optJSONObject("assessment")?.optString("kind") in setOf(
                "violation",
                "wanted",
                "reckless"
            )
        ) {
            val ui = PhoneAccountUi(this, light)
            val scale = (resources.configuration.screenWidthDp / 1100f).coerceIn(1f, 1.65f)
            content.addView(ui.button("‹ 기록") { closeEventDetail() }
                .apply { layoutParams = LinearLayout.LayoutParams(ui.dp(220), -2) })
            val compactPlate = event.optJSONObject("assessment")
                ?.optString("kind") == "wanted" && PlateAnalysis.present(event) &&
                    event.optJSONObject("video")
                        ?.optString("input_kind") == "authored_moving_plate_scene"
            val mediaActions = column()
            EventVisualBrief.render(
                ui, content, event, client,
                allowed = { foreground && !driving && opened?.optString("id") == event.optString("id") })
            if (compactPlate) content.addView(mediaActions)
            val trafficDetails = column()
            val explanations = trafficDetails
            TrafficCasePanel.render(
                ui,
                explanations,
                event,
                client,
                allowed = { foreground && !driving && opened?.optString("id") == event.optString("id") })
            if (event.optBoolean("evidence_valid") && event.optJSONObject("traffic_review")
                    ?.optString("stage") == "synthetic_review"
            ) {
                val state = event.optString("status")
                if (state == "pending_confirmation") {
                    explanations.addView(ui.button("시연 자료 확인", true) { confirm(event, true) }
                        .apply { textSize = 22f * scale })
                    explanations.addView(ui.button("이번 시연에서 제외") { confirm(event, false) }
                        .apply { textSize = 22f * scale })
                } else if (event.optJSONObject("assessment")
                        ?.optString("kind") != "reckless" && state in setOf(
                        "evidence_ready",
                        "dispatch_ready",
                        "retry_pending"
                    )
                )
                    explanations.addView(ui.button("모의 수신처로 보내기", true) { dispatch(event) }
                        .apply { textSize = 22f * scale })
            }
            val decoded = event.optJSONObject("traffic_review")?.optJSONObject("relative_motion")
                ?.optString("source") == "synthetic_video_tracking" || event.optJSONObject("traffic_review")
                ?.optJSONObject("stationary_motion")
                ?.optString("source") == "synthetic_video_tracking"
            if (!decoded) {
                val scenarioSettings = column()
                scenarioSettings.addView(ui.button("주변 차량 자료") { runScenario("violation") }
                    .apply { textSize = 22f * scale })
                scenarioSettings.addView(ui.button("시험 목록 자료") { runScenario("wanted") }
                    .apply { textSize = 22f * scale })
                scenarioSettings.addView(ui.button("주행 변화 자료") { runScenario("reckless") }
                    .apply { textSize = 22f * scale })
                ui.disclosure(
                    explanations, "시연 설정", scenarioSettings,
                    collapsedLabel = "시연 설정 펼치기", expandedLabel = "시연 설정 접기"
                )
            }
            val scene = event.optJSONObject("video")
            val back = ui.button(
                if (roadGroupPage != null && selected == "집계") "묶음 원본으로 돌아가기" else "사건 근거 목록",
                true
            ) { closeEventDetail() }.apply { textSize = 22f * scale }
            if (event.optBoolean("evidence_valid") && scene?.optString("source") == "synthetic_scene_fixture" && scene.optBoolean(
                    "integrity_valid"
                )
            ) {
                val actions = row()
                actions.addView(ui.button("영상 보기") {
                    openComparisonVideo(event.getString("id"), event.getString("id"))
                }.apply { textSize = 22f * scale }, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(back, LinearLayout.LayoutParams(0, -2, 1f))
                if (compactPlate) mediaActions.addView(actions) else trafficDetails.addView(
                    actions,
                    0
                )
            } else if (compactPlate) mediaActions.addView(back) else trafficDetails.addView(back)
            ui.disclosure(
                content,
                "상세 기록",
                trafficDetails,
                collapsedLabel = "상세 기록 펼치기",
                expandedLabel = "상세 기록 접기"
            )
            return
        }
        if ((event.optString("source") == "synthetic" && event.optJSONObject("assessment")
                ?.optString("kind") == "observation") || event.optString("status") == "input_review_required" || event.optJSONObject(
                "assessment"
            )?.optString("kind") in setOf(
                "pothole",
                "parking",
                "crash",
                "diagnosis",
                "rain",
                "violation",
                "reckless",
                "wanted"
            ) || EventBriefState.isVoltageObservation(event) || EventBriefState.isRoadObservation(
                event
            )
        ) {
            val ui = PhoneAccountUi(this, light)
            val actionsBody = column()
            if (demoMode && RoadObservationSource.reference(event) != null)
                actionsBody.addView(ui.button("이후 관측 원본") { openRoadSource(event) })
            val back =
                ui.button(if (roadGroupPage != null && selected == "집계") "묶음 원본으로 돌아가기" else "‹ 기록") { if (!busy) closeEventDetail() }
            val scene = event.optJSONObject("video")
            if (demoMode && scene?.optString("source") == "synthetic_scene_fixture" && scene.optBoolean(
                    "integrity_valid"
                )
            ) {
                actionsBody.addView(ui.button("영상 보기") {
                    openComparisonVideo(event.getString("id"), event.getString("id"))
                })
            }
            if (demoMode && event.opt("evidence_valid") == true) {
                val automotive = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
                val id = event.getString("id")
                val button = ui.button(
                    if (automotive) exportLabel(id) else if (event.optJSONObject("assessment")
                            ?.optString("kind") == "parking"
                    ) "기록 저장" else "비교 자료 저장"
                ) { requestEvidenceExport(event) }
                if (automotive) {
                    exportButton = button
                    exportButtonEventId = id
                    button.isEnabled = activeExportId != id
                }
                actionsBody.addView(button)
            }
            if (demoMode && !BuildConfig.CONTEST_DEMO) {
                val developer = column()
                eventDetailDeveloper(event, developer)
                PhoneAccountUi(this, light).disclosure(actionsBody, "개발용 합성 처리 시험", developer)
            }
            VehicleEventDetail.render(
                this,
                content,
                event,
                light,
                actionsBody,
                if (demoMode) client else null,
                allowed = { foreground && !driving && opened?.optString("id") == event.optString("id") },
                onOpenDiagnostic = { onOpenDiagnosticReview(event) },
                onEventChanged = { onDemoActionEventChanged(event, it) })
            if (demoMode && !driving && EventBriefState.from(event).title == "보험 연결 실패") {
                content.addView(ui.button("보험사 다시 연결") {
                    if (foreground && !driving && !busy && opened?.optString("id") == event.optString(
                            "id"
                        )
                    ) dispatch(event)
                }, 2)
            }
            content.addView(back, 0, LinearLayout.LayoutParams(dp(220), -2))
            return
        }
        eventDetailDeveloper(event, content)
    }

    private fun eventDetailDeveloper(event: JSONObject, content: LinearLayout) {
        val a = event.getJSONObject("assessment")
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹ 기록") {
            opened = null
            selected = "기록"
            render()
        }, LinearLayout.LayoutParams(dp(120), dp(70)))
        header.addView(
            text(a.getString("title"), 30f, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(18) })
        header.addView(text(stateLabel(event.getString("status")), 18f, amber))
        content.addView(header)
        content.addView(text("가상 자료 · 외부 기관에 전송되지 않습니다", 16f, muted))
        RoadFollowupPanel.render(PhoneAccountUi(this, light), content, event)
        RoadEvidencePanel.render(PhoneAccountUi(this, light), content, event)
        CrashCasePanel.render(PhoneAccountUi(this, light), content, event)
        if (a.getString("kind") == "diagnosis") {
            DiagnosticCasePanel.render(PhoneAccountUi(this, light), content, event)
            if (event.optBoolean("diagnostic_valid")) content.addView(
                SignalChartView(
                    this,
                    event.getJSONObject("data").getJSONArray("samples"),
                    light
                ), LinearLayout.LayoutParams(-1, dp(210))
            )
            val repair = event.optJSONObject("repair")
            if (repair == null) {
                content.addView(button("보증 적용 · 시험 지정 센터 연결", true) {
                    repairOffer(
                        event,
                        "covered"
                    )
                })
                content.addView(button("보증 외 · 시험 정비소 연결") { repairOffer(event, "not_covered") })
            } else {
                val post = repair.optJSONObject("post_repair")
                if (post == null) {
                    content.addView(button("수리 후 재측정 · 개선 시험", true) {
                        repairComplete(
                            event,
                            "healthy"
                        )
                    })
                    content.addView(button("수리 후 재측정 · 미해결 시험") {
                        repairComplete(
                            event,
                            "diagnosis"
                        )
                    })
                }
            }
        }
        if (demoMode && a.getString("kind") == "pothole" && event.getBoolean("evidence_valid")) {
            content.addView(button("후속 기록 연결 · 합성 시험") {
                val candidates = (0 until events.length()).map { events.getJSONObject(it) }.filter {
                    it.optString("id") != event.getString("id") && it.optJSONObject("data")
                        ?.has("capture_started_at") == true &&
                            it.optJSONObject("assessment")?.optString("kind") in setOf(
                        "pothole",
                        "observation"
                    )
                }
                if (candidates.isEmpty()) Toast.makeText(
                    this,
                    "관측 시각이 있는 이후 기록이 필요합니다",
                    Toast.LENGTH_LONG
                ).show()
                else android.app.AlertDialog.Builder(this).setTitle("연결할 합성 관측 기록")
                    .setItems(candidates.map {
                        it.getJSONObject("assessment")
                            .getString("title") + " · " + it.getString("id").take(8)
                    }.toTypedArray()) { _, index ->
                        task {
                            opened = JSONObject(
                                client.request(
                                    "/api/events/${event.getString("id")}/road-followups",
                                    JSONObject().put(
                                        "followup_event_id",
                                        candidates[index].getString("id")
                                    )
                                )
                            )
                        }
                    }.setNegativeButton("취소", null).show()
            })
        }
        if (event.getBoolean("evidence_valid")) {
            val contextDetails = column().apply { visibility = View.GONE }
            scenarioContext(event.getJSONObject("data").optJSONObject("context"), contextDetails)
            val toggle = button("상황·차량 상세 펼치기") {}
            toggle.setOnClickListener {
                val expanded = contextDetails.visibility != View.VISIBLE
                contextDetails.visibility = if (expanded) View.VISIBLE else View.GONE
                toggle.text = if (expanded) "상황·차량 상세 접기" else "상황·차량 상세 펼치기"
            }
            content.addView(toggle)
            content.addView(contextDetails)
        }
        val data = event.getJSONObject("data")
        if (event.getBoolean("evidence_valid")) content.addView(button("증거 묶음 저장 · 가상 자료") {
            requestEvidenceExport(
                event
            )
        })
        card(
            content,
            "증거 자료",
            "센서 ${
                data.getJSONArray("samples").length()
            }개 / ${data.getInt("sample_hz")} Hz\n원본 해시: ${
                event.getString("hash").take(16)
            }…\n무결성: ${if (event.getBoolean("evidence_valid")) "일치" else "불일치"}\n영상: ${
                if (event.isNull(
                        "video"
                    )
                ) "미취득" else "합성 영상 첨부 · 실제 촬영 아님"
            }"
        )
        val video = event.optJSONObject("video")
        if (video == null) content.addView(button("전후 영상 보관 시험") {
            task {
                client.request("/api/events/${event.getString("id")}/video-fixture", JSONObject())
                opened = JSONObject(client.request("/api/events/${event.getString("id")}"))
            }
        })
        else if (video.optBoolean("integrity_valid")) content.addView(button("합성 영상 재생") {
            if (!driving) startActivity(
                android.content.Intent(this, VideoActivity::class.java)
                    .putExtra("event_id", event.getString("id"))
                    .putExtra("simulation", drivingState.isSimulation)
            )
        })
        if (event.getString("status") == "pending_confirmation") {
            content.addView(button("도로 상황 확인 · 접수 준비", true) { confirm(event, true) })
            content.addView(button("해당하지 않음") { confirm(event, false) })
        } else if (event.getString("status") in listOf(
                "dispatch_ready",
                "dispatch_retry"
            ) && !a.isNull("recipient")
        ) {
            content.addView(button("모의 수신처로 접수", true) { dispatch(event) })
        }
        val history = event.getJSONArray("history")
        val lines = (0 until history.length()).map { history.getJSONObject(it).getString("action") }
        card(content, "처리 이력", lines.joinToString("\n"))
        val deliveries = event.getJSONArray("dispatches")
        for (i in 0 until deliveries.length()) {
            val d = deliveries.getJSONObject(i)
            card(
                content,
                "모의 접수 결과",
                "${d.getString("recipient")}\n${d.optString("receipt", "접수 대기")}",
                blue
            )
            d.optJSONObject("payload")?.let { payload ->
                val vehicle = payload.getJSONObject("vehicle")
                val policy = payload.getJSONObject("insurance")
                card(
                    content,
                    "접수 시 전달한 내 차량 정보",
                    "${vehicle.getString("plate")} · ${vehicle.getString("vin")}\n${
                        policy.getString("provider_name")
                    } · ${policy.getString("policy_id")}\n자료 해시: ${
                        d.getString("payload_hash").take(16)
                    }…\n${if (d.optBoolean("payload_valid")) "접수 자료 원본 일치" else "접수 자료 확인 필요"}"
                )
            }
        }
    }

    private fun scenarioContext(context: JSONObject?, parent: LinearLayout = content) {
        if (context == null) {
            parent.addView(text("이전 기록에는 상세 상황 정보가 없습니다.", 18f, muted))
            return
        }
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(20))
            setBackgroundColor(panel)
        }
        section.addView(text("상황과 차량", 25f, ink, true))
        section.addView(text("사례 설정 · 번호판·차종 인식 결과가 아닙니다", 16f, amber))
        val columns = LinearLayout(this)
        val vehicles = column()
        val factsColumn = column().apply { setPadding(dp(26), 0, 0, 0) }
        columns.addView(vehicles, LinearLayout.LayoutParams(0, -2, 1.1f))
        columns.addView(factsColumn, LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(columns)
        fun row(parent: LinearLayout, label: String, value: String) {
            val line = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            line.addView(text(label, 18f, muted), LinearLayout.LayoutParams(dp(120), -2))
            line.addView(text(value, 21f, ink), LinearLayout.LayoutParams(0, -2, 1f))
            parent.addView(line)
            parent.addView(View(this).apply {
                setBackgroundColor(
                    if (light) Color.parseColor("#DEDEDE") else Color.parseColor(
                        "#454545"
                    )
                )
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }

        val actors = context.optJSONArray("actors") ?: JSONArray()
        if (actors.length() == 0) row(vehicles, "주변 차량", "관측 정보 없음")
        for (i in 0 until actors.length()) {
            val actor = actors.getJSONObject(i)
            row(
                vehicles,
                "주변 차량 ${i + 1}",
                "${
                    actor.optString(
                        "plate",
                        "번호 미확인"
                    )
                }\n${actor.optString("brand")} ${
                    actor.optString(
                        "model",
                        "차종 미확인"
                    )
                } · ${actor.optString("color", "색상 미확인")}"
            )
            row(vehicles, "관측 관계", actor.optString("relationship", "미확인"))
        }
        context.optJSONObject("own_vehicle")?.let { own ->
            row(
                vehicles,
                "내 차량",
                "${own.optString("model", "미확인")} · ${
                    own.optString(
                        "color",
                        "미확인"
                    )
                }\n${own.optString("plate", "미확인")}"
            )
        }
        row(vehicles, "위치", context.optString("location_label", "미확인"))
        val facts = context.optJSONArray("facts") ?: JSONArray()
        for (i in 0 until facts.length()) {
            val fact = facts.getJSONObject(i)
            row(factsColumn, fact.optString("label"), fact.optString("value"))
        }
        val unknowns = context.optJSONArray("unknowns") ?: JSONArray()
        if (unknowns.length() > 0) factsColumn.addView(
            text(
                "확인이 필요한 내용\n" + (0 until unknowns.length()).joinToString(
                    "\n"
                ) { "• " + unknowns.getString(it) }, 17f, muted
            )
        )
        parent.addView(section, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
            bottomMargin = dp(16)
        })
    }

    @Deprecated("Legacy Activity result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 81) return
        val id = pendingExportId
        pendingExportId = null
        val uri = data?.data
        if (resultCode != RESULT_OK || id == null || uri == null) return
        if (driving) {
            reportExportResult("주차 후 다시 저장해 주세요")
            return
        }
        executor.execute {
            val message = try {
                val payload = client.evidencePackage(id)
                val checked = java.io.File.createTempFile("comparison-export-", ".zip", cacheDir)
                val bundled = try {
                    checked.writeBytes(payload)
                    EventMapExport.bundle(checked, id, filesDir)
                } finally {
                    checked.delete()
                }
                val stream = contentResolver.openOutputStream(uri, "w") ?: error("저장 위치를 열지 못했습니다")
                stream.use { it.write(bundled) }
                "합성 자료 저장 완료 · 외부 제출 없음"
            } catch (error: AccountAccessException) {
                if (error.reason == AccountAccessException.Reason.INTEGRITY) "자료 확인 실패로 저장하지 않았습니다" else "자료를 받지 못했습니다 · 연결을 확인해 주세요"
            } catch (_: Exception) {
                "저장하지 못했습니다 · 연결과 저장 공간을 확인해 주세요"
            }
            runOnUiThread { reportExportResult(message) }
        }
    }

    private fun settingRow(
        parent: LinearLayout,
        title: String,
        value: String,
        detail: String,
        action: () -> Unit
    ) {
        val item = row().apply {
            minimumHeight = dp(104)
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = surface(if (light) c("#F5F5F5") else c("#242424"), 14f)
            isClickable = true
            isFocusable = true
            contentDescription = "$title, $value. $detail"
            setOnClickListener { if (!busy) action() }
        }
        val words = column()
        words.addView(text(title, 22f, ink, true))
        words.addView(text(detail, 16f, muted))
        item.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        item.addView(text("$value  ›", 19f, blue, true).apply { setPadding(dp(18), 0, 0, 0) })
        parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
    }

    private fun settings() {
        content.addView(text("설정", 32f, ink, true))
        val panels = row().apply { gravity = Gravity.TOP }
        val controls = column()
        val connection = column()
        if (resources.configuration.screenWidthDp < 850) {
            content.addView(controls)
            content.addView(connection)
        } else {
            panels.addView(
                controls,
                LinearLayout.LayoutParams(0, -2, 1.15f).apply { rightMargin = dp(28) })
            panels.addView(connection, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(panels)
        }
        controls.addView(text("화면과 차량 상태", 17f, muted))
        settingRow(
            controls, "화면 모드", when (displayMode) {
                "day" -> "주간"
                "night" -> "야간"
                else -> "차량 설정"
            }, "차량 설정 또는 주간·야간 선택"
        ) {
            val modes = arrayOf("system", "day", "night")
            android.app.AlertDialog.Builder(this).setTitle("화면 모드")
                .setSingleChoiceItems(
                    arrayOf("차량 설정에 맞춤", "주간", "야간"),
                    modes.indexOf(displayMode).coerceAtLeast(0)
                ) { dialog, index ->
                    displayMode = modes[index]
                    getSharedPreferences("display", MODE_PRIVATE).edit()
                        .putString("mode", displayMode).apply()
                    applyDisplayMode()
                    dialog.dismiss()
                    render()
                }.setNegativeButton("취소", null).show()
        }
        if (demoMode) {
            settingRow(
                controls,
                "주차 중 비교 음성",
                if (comparisonSpeechEnabled) "켜짐" else "꺼짐",
                "주행 중에는 재생하지 않습니다"
            ) {
                comparisonSpeechEnabled = !comparisonSpeechEnabled
                getSharedPreferences("comparison_speech", MODE_PRIVATE).edit()
                    .putBoolean("enabled", comparisonSpeechEnabled).apply()
                updateComparisonSpeech()
                render()
            }
            if (BuildConfig.DEBUG) settingRow(
                controls,
                "차량 상태 입력",
                if (drivingState.isSimulation) "가상" else "Pleos",
                "개발 시험용 전환"
            ) {
                drivingState.useSimulation(!drivingState.isSimulation)
                render()
            }
            if (drivingState.isSimulation) settingRow(
                controls,
                "가상 운행",
                if (simulatedDriving) "주행" else "정차",
                "화면 조작 제한 시험"
            ) {
                simulatedDriving = !simulatedDriving
                drivingState.setSimulatedMoving(simulatedDriving)
                render()
            }
        }
        connection.addView(text("연결 상태", 17f, muted))
        val box = column().apply {
            background = surface(if (light) c("#F5F5F5") else c("#242424"), 14f)
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        box.addView(text("Pleos Vehicle ${BuildConfig.PLEOS_SDK_VERSION}", 24f, ink, true))
        box.addView(text("SDK 포함됨 · 차량 응답은 아래 상태로 확인", 16f, muted))
        box.addView(text(sdkStatus, 19f, amber))
        box.addView(text(drivingState.label(android.os.SystemClock.elapsedRealtime()), 18f, muted))
        box.addView(
            text(
                if (demoMode) "센서·기관 접수는 가상 자료로 시험합니다." else "외부 제출·비교 자료 연동 확인 전",
                17f,
                muted
            )
        )
        connection.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
        if (!driving) settingRow(connection, "차량 기록", "열기", "사건 목록과 차량 정보 확인") {
            selected = "기록"
            opened = null
            render()
        }
        if (driving) {
            locked()
            return
        }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            settingRow(
                content,
                "지도 위치",
                locationMonitor?.status ?: "확인 필요",
                "앱을 보는 동안 GPS 사용 · 위치는 사건 자료로 전송하지 않음"
            ) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ), 203
                    )
                else {
                    locationMonitor?.start()
                    render()
                }
            }
            val speedGranted =
                checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED
            settingRow(
                content,
                "차량 속도 조회",
                if (speedGranted) "허용됨" else "허용 필요",
                "차량 신호 확인용 · 시나리오 속도와 별도"
            ) {
                if (!speedGranted) requestPermissions(
                    arrayOf("android.car.permission.CAR_SPEED"),
                    202
                )
                else {
                    adapter.startSpeedMonitoring()
                    render()
                }
            }
        }
        if (demoMode) {
            settingRow(
                content,
                "다른 앱 위 이벤트 안내",
                if (DemoEventMonitorService.running) "끄기" else "켜기",
                "음악·내비 화면 유지 · 30분간 안내"
            ) {
                if (DemoEventMonitorService.running) stopService(
                    Intent(
                        this,
                        DemoEventMonitorService::class.java
                    )
                )
                else if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 204)
                else startEventSession()
                render()
                timer.postDelayed(
                    { if (foreground && selected == "설정" && !isDestroyed) render() },
                    500
                )
            }
            settingRow(
                content,
                "정비 비교 시연 관리",
                "측정 만들기",
                "합성 시험 자료 · 실제 차량 측정은 아직 연결되지 않았습니다"
            ) { runScenario("diagnosis") }
            settingRow(content, "응답 대기 시연", "열기", "합성 응답·취소·시간 만료 확인") {
                startActivity(
                    Intent(
                        this,
                        EmergencyDemoActivity::class.java
                    ).putExtra("light", light)
                )
            }
            settingRow(content, "고객·그룹 가치 시연", "열기", "기록 확보에서 선제 점검 안내까지") {
                startActivity(
                    Intent(
                        this,
                        ContestValueActivity::class.java
                    ).putExtra("light", light)
                )
            }
            settingRow(content, "내비 배경 시연", "열기", "공모전 지도 배경 위 이벤트 안내") {
                startActivity(
                    Intent(
                        this,
                        ContestNavigationActivity::class.java
                    ).putExtra("light", light)
                )
            }
            settingRow(content, "도로 자료 집계 시연", "열기", "관측 묶음과 기관 제공 기록") {
                task {
                    roadSummary = JSONObject(client.request("/api/demo/road-summary"))
                    selected = "집계"
                }
            }
            val sdkDetails = column()
            sdkDetails.addView(
                VehicleSignalsPanel(this, light) { adapter.diagnosticSnapshot() },
                LinearLayout.LayoutParams(-1, -2)
            )
            PhoneAccountUi(this, light).disclosure(content, "개발용 SDK 신호 확인", sdkDetails)
            settingRow(content, "기존 도로 안내 시험", "열기", "기존 서비스 중복 · 제품 기능에서 제외한 개발 화면") {
                selected = "도로"
                opened = null
                render()
            }
            val legacy = column()
            AccountEventList.render(PhoneAccountUi(this, light), legacy, events) { id ->
                task { opened = JSONObject(client.request("/api/events/$id")) }
            }
            PhoneAccountUi(this, light).disclosure(content, "개발용 전체 시험 기록 · 제품 범위와 별도", legacy)
            settingRow(
                content,
                "시나리오 시험",
                if (showTestScenarios) "접기" else "펼치기",
                "가상 사건 입력 · 실제 기관 전송 없음"
            ) {
                showTestScenarios = !showTestScenarios
                render()
            }
            if (showTestScenarios) {
                val cases = linkedMapOf(
                    "pothole" to "포트홀",
                    "speed_bump" to "방지턱 비교",
                    "rain" to "폭우",
                    "washer" to "워셔 비교",
                    "diagnosis" to "전압 이상",
                    "healthy" to "정상 비교",
                    "crash" to "사고·보험",
                    "emergency" to "무응답·119",
                    "parking" to "주차 충격",
                    "violation" to "주정차 위반 후보",
                    "reckless" to "급격한 주행",
                    "wanted" to "시험 수배 목록"
                )
                val columns = if (resources.configuration.screenWidthDp < 850) 1 else 3
                cases.entries.toList().chunked(columns).forEach { group ->
                    val line = row()
                    group.forEach { (id, label) ->
                        line.addView(
                            button(label) { runScenario(id) },
                            LinearLayout.LayoutParams(0, dp(80), 1f)
                                .apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
                    }
                    repeat(columns - group.size) {
                        line.addView(
                            View(this),
                            LinearLayout.LayoutParams(0, 1, 1f)
                        )
                    }
                    content.addView(line)
                }
            }
        }
    }

    private fun locked() {
        card(
            content,
            if (drivingState.isSimulation) "정차 후 확인할 수 있습니다" else "주차 상태 확인이 필요합니다",
            if (drivingState.isSimulation) "주행 중에는 자세한 기록과 시험 설정을 열 수 없습니다." else "차량 연결과 P단 상태를 확인한 뒤 자세한 기록을 열 수 있습니다."
        )
    }

    /** 최종 텍스트를 처리하는 공통 경로. 디버그 주입은 마이크 인식 결과로 보지 않는다 */
    private fun handleVoiceResult(phrase: String?) {
        if (!voiceListening) return
        voiceListening = false
        if (!voiceAllowed()) return
        when (VoiceCommand.parse(phrase)) {
            VoiceCommand.Action.DIAGNOSE -> {
                voiceMessage = "진단 시작"
                voiceFallback = false
                runScenario("diagnosis")
            }

            VoiceCommand.Action.CANCEL -> {
                voiceMessage = "진단 요청 취소"
                voiceFallback = false
                render()
            }

            VoiceCommand.Action.NO_RESULT -> {
                voiceMessage = "음성 인식 실패 · 버튼으로 진행할 수 있습니다"
                voiceFallback = true
                render()
            }

            VoiceCommand.Action.UNSUPPORTED -> {
                voiceMessage = "‘차량 진단’이라고 말씀해 주세요"
                voiceFallback = true
                render()
            }
        }
    }

    private fun voiceAllowed() = demoMode && foreground && !driving && !busy && selected == "차량"
    private fun consumeVoicePermission() {
        if (!demoMode || driving || selected != "차량") {
            pendingVoicePermission = null
            voicePermissionRequested = false
            return
        }
        if (!voiceAllowed() || isDestroyed || isFinishing) return
        val granted = pendingVoicePermission ?: return
        pendingVoicePermission = null
        voicePermissionDenied =
            !granted || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (voicePermissionDenied) {
            voiceMessage = "마이크 권한 없음 · 버튼으로 진행할 수 있습니다"
            voiceFallback = true
            render()
        } else startVoice()
    }

    private fun startVoice() {
        if (!voiceAllowed() || !::voice.isInitialized) return
        if (!voice.available()) {
            voiceMessage = "음성 입력 미지원 · 버튼으로 진행할 수 있습니다"
            voiceFallback = true
            render()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePermissionRequested = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 201)
            return
        }
        voicePermissionDenied = false
        voiceListening = true
        voiceFallback = false
        if (!voice.start()) {
            voiceListening = false
            voiceFallback = true
            voiceMessage = "음성 입력을 시작하지 못했습니다 · 버튼으로 진행하세요"
        } else voiceMessage = "말씀해 주세요: 차량 진단"
        render()
    }

    private fun startEventSession() {
        if (!demoMode || !foreground || driving) return
        if (!DriverEventNotification.available(this)) {
            android.widget.Toast.makeText(
                this,
                "설정에서 이벤트 알림을 허용해 주세요",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        runCatching { startForegroundService(Intent(this, DemoEventMonitorService::class.java)) }
            .onFailure {
                android.widget.Toast.makeText(
                    this,
                    "이벤트 안내를 시작하지 못했습니다",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201) {
            if (!voicePermissionRequested) return
            voicePermissionRequested = false
            if (!demoMode || driving || selected != "차량") {
                pendingVoicePermission = null
                return
            }
            pendingVoicePermission = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            // AAOS는 화면이 다시 켜지기 전이나 새로고침 중에도 결과를 보낼 수 있다. 같은 정차 화면이 그려질 때까지 보관한다
            if (foreground) render()
        }
        if (requestCode == 202) {
            adapter.startSpeedMonitoring()
            render()
        }
        if (requestCode == 203) {
            locationMonitor?.start()
            render()
        }
        if (requestCode == 204 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            pendingEventSession = true
            if (foreground) {
                pendingEventSession = false
                startEventSession()
                render()
            }
        }
    }

    private fun stateLabel(state: String) = when (state) {
        "input_review_required" -> "입력 확인 필요 · 판정 보류"
        "pending_confirmation" -> "운전자 확인 대기"
        "evidence_ready" -> "증거 자료 준비됨"
        "dispatch_ready" -> "접수 준비됨"
        "acknowledged" -> "모의 수신처 접수 확인"
        "dismissed" -> "신고 대상에서 제외"
        "dispatch_retry" -> "전송 재시도 대기"
        "service_accepted" -> "시험 정비소 접수 확인"
        "repair_reviewed" -> "수리 후 측정 비교됨"
        "insurance_check_required" -> "보험 계약 확인 필요 · 접수 대기"
        else -> state
    }

    private fun clearDiagnosticReview() {
        diagnosticReviewGeneration++
        diagnosticReviewParent = null
        diagnosticReview = null
        diagnosticReviewFailed = false
        diagnosticReviewSelection = null
        if (diagnosticReviewLoading) {
            diagnosticReviewLoading = false
            busy = false
        }
    }

    private fun onOpenDiagnosticReview(event: JSONObject) {
        if (!demoMode || !foreground || driving || busy || !DiagnosticEvidenceReview.canOpen(event)) return
        val id = event.optString("id")
        val hash = event.optString("hash")
        if (runCatching { AccountEventRoute.checked(id) }.getOrNull() != id || !hash.matches(Regex("[0-9a-f]{64}")) || event.optString(
                "source"
            ) != "synthetic" ||
            event.optJSONObject("assessment")?.optString("kind") != "diagnosis"
        ) return
        val visible = opened
            ?: if (selected == "차량") (0 until events.length()).mapNotNull { events.optJSONObject(it) }
                .firstOrNull {
                    it.optJSONObject("assessment")?.optString("kind") == "diagnosis"
                } else null
        if (visible?.optString("id") != id || visible?.optString("hash") != hash) return
        val selection = selected
        clearDiagnosticReview()
        clearRoadSource()
        val ticket = ++diagnosticReviewGeneration
        diagnosticReviewSelection = selection
        diagnosticReviewParent = event
        diagnosticReviewLoading = true
        busy = true
        opened = event
        render()
        executor.execute {
            val response = runCatching {
                JSONObject(client.request("/api/events/$id")).also {
                    check(
                        it.optString("id") == id && it.optString("hash") == hash && it.optString("source") == "synthetic" &&
                                it.optJSONObject("assessment")?.optString("kind") == "diagnosis"
                    )
                }
            }
            runOnUiThread {
                if (ticket != diagnosticReviewGeneration || isDestroyed) return@runOnUiThread
                diagnosticReviewLoading = false
                busy = false
                if (!demoMode || !foreground || driving || selected != selection || opened?.optString(
                        "id"
                    ) != id || opened?.optString("hash") != hash
                ) {
                    clearDiagnosticReview()
                    return@runOnUiThread
                }
                response.fold(onSuccess = {
                    diagnosticReview = it
                    opened = it
                }, onFailure = {
                    diagnosticReviewFailed = true
                    diagnosticReview = null
                })
                render()
            }
        }
    }

    private fun returnFromDiagnosticReview() {
        if (!demoMode || !foreground || driving || diagnosticReviewLoading || busy) return
        val parent = diagnosticReviewParent ?: return
        val id = parent.optString("id")
        val hash = parent.optString("hash")
        val selection = diagnosticReviewSelection
        if (selected != selection || opened?.optString("id") != id || opened?.optString("hash") != hash) {
            clearDiagnosticReview()
            render()
            return
        }
        val ticket = ++diagnosticReviewGeneration
        diagnosticReview = null
        diagnosticReviewFailed = false
        diagnosticReviewLoading = true
        busy = true
        render()
        executor.execute {
            val response = runCatching {
                JSONObject(client.request("/api/events/$id")).also {
                    check(
                        it.optString("id") == id && it.optString("hash") == hash && it.optString("source") == "synthetic" &&
                                it.optJSONObject("assessment")?.optString("kind") == "diagnosis"
                    )
                }
            }
            runOnUiThread {
                if (ticket != diagnosticReviewGeneration || isDestroyed) return@runOnUiThread
                diagnosticReviewLoading = false
                busy = false
                if (!demoMode || !foreground || driving || selected != selection || opened?.optString(
                        "id"
                    ) != id || opened?.optString("hash") != hash
                ) {
                    clearDiagnosticReview()
                    return@runOnUiThread
                }
                response.fold(onSuccess = {
                    clearDiagnosticReview()
                    opened = it
                }, onFailure = {
                    diagnosticReviewFailed = true
                    diagnosticReview = null
                })
                render()
            }
        }
    }

    private fun clearRoadSource() {
        roadSourceGeneration++
        roadSource = null
        roadSourceParent = null
        roadSourceFailed = false
        if (roadSourceLoading) {
            roadSourceLoading = false
            busy = false
        }
    }

    private fun openRoadSource(parent: JSONObject) {
        if (!demoMode || !foreground || driving || busy || RoadObservationSource.reference(parent) == null) return
        val ticket = ++roadSourceGeneration
        roadSourceParent = parent
        roadSource = null
        roadSourceFailed = false
        roadSourceLoading = true
        busy = true
        render()
        executor.execute {
            val response = runCatching { RoadObservationSource.load(client, parent) }
            runOnUiThread {
                if (ticket != roadSourceGeneration || isDestroyed) return@runOnUiThread
                roadSourceLoading = false
                busy = false
                if (!foreground || driving || opened?.optString("id") != parent.optString("id")) {
                    clearRoadSource()
                    return@runOnUiThread
                }
                response.fold(onSuccess = {
                    opened = it.first
                    roadSourceParent = it.first
                    roadSource = it.second
                }, onFailure = {
                    roadSourceFailed = true
                    roadSource = null
                })
                render()
            }
        }
    }

    private fun returnFromRoadSource() {
        if (!foreground || driving) return
        val parent = roadSourceParent ?: return
        val id = parent.optString("id")
        clearRoadSource()
        val ticket = ++roadSourceGeneration
        roadSourceParent = parent
        roadSourceLoading = true
        busy = true
        render()
        executor.execute {
            val response = runCatching {
                JSONObject(client.request("/api/events/$id")).also {
                    check(it.optString("id") == id && it.optString("source") == "synthetic")
                }
            }
            runOnUiThread {
                if (ticket != roadSourceGeneration || isDestroyed) return@runOnUiThread
                roadSourceLoading = false
                busy = false
                if (!foreground || driving || opened?.optString("id") != id) {
                    clearRoadSource()
                    return@runOnUiThread
                }
                response.fold(onSuccess = {
                    clearRoadSource()
                    opened = it
                }, onFailure = { roadSourceFailed = true })
                render()
            }
        }
    }

    private fun closeEventDetail() {
        clearRoadSource()
        opened = null
        if (roadGroupPage == null || selected != "집계") selected = "기록"
        render()
    }

    private fun openRoadGroupEvent(id: String) {
        if (!demoMode || driving || busy) return
        val rows = roadGroupPage?.optJSONArray("events") ?: return
        val original = (0 until rows.length()).map { rows.getJSONObject(it) }
            .firstOrNull { it.optString("id") == id } ?: return
        task {
            val fresh = runCatching { JSONObject(client.request("/api/events/$id")) }.getOrNull()
            if (fresh != null && fresh.opt("evidence_valid") == true && fresh.optString("source") == "synthetic" && fresh.optString(
                    "hash"
                ) == original.optString("hash") &&
                fresh.optString("status") !in setOf(
                    "pending_confirmation",
                    "dismissed"
                ) && fresh.optJSONObject("assessment")?.optString("kind") == "pothole"
            ) opened = fresh
            else {
                opened = null
                roadGroupPage = JSONObject()
            }
            selected = "집계"
        }
    }

    private fun openRoadGroup(id: String, offset: Int, snapshot: String? = null) {
        if (!demoMode || driving || busy || !id.matches(Regex("[0-9a-f]{16}")) || offset !in 0..100000) return
        if (snapshot != null && !snapshot.matches(Regex("[0-9a-f]{64}"))) return
        task {
            roadGroupPage = runCatching {
                JSONObject(client.request("/api/demo/road-summary/groups/$id" + (snapshot?.let { "/$offset/$it" }
                    ?: "")))
            }.getOrElse { JSONObject() }
            opened = null
            selected = "집계"
        }
    }

    private fun task(work: () -> Unit) {
        if (busy) return
        busy = true
        requestFailed = false
        if (foreground) render()
        executor.execute {
            try {
                work()
                runOnUiThread {
                    if (!isDestroyed) {
                        busy = false
                        if (foreground) render()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        busy = false
                        requestFailed = true
                        if (foreground) render()
                    }
                }
            }
        }
    }

    private fun refresh() {
        if (!demoMode) return
        task {
            events = JSONArray(client.request("/api/events"))
            vehicleProfile = JSONObject(client.request("/api/demo/vehicle"))
        }
    }

    private fun runScenario(name: String) {
        if (!demoMode) return
        task {
            val event = JSONObject(
                client.request(
                    "/api/scenarios/run",
                    JSONObject().put("scenario", name)
                        .put("request_key", UUID.randomUUID().toString())
                )
            )
            events = JSONArray(client.request("/api/events"))
            opened = if (driving) null else event
            selected = if (driving) "홈" else "기록"
        }
    }

    private fun confirm(event: JSONObject, accepted: Boolean) = task {
        opened = JSONObject(
            client.request(
                "/api/events/${event.getString("id")}/confirm",
                JSONObject().put("accepted", accepted)
            )
        )
        events = JSONArray(client.request("/api/events"))
    }

    private fun dispatch(event: JSONObject, recipient: String? = null) = task {
        val body = JSONObject()
        if (recipient != null) body.put("recipient", recipient)
        client.request("/api/events/${event.getString("id")}/dispatch", body)
        opened = JSONObject(client.request("/api/events/${event.getString("id")}"))
        events = JSONArray(client.request("/api/events"))
    }

    private fun repairOffer(event: JSONObject, warranty: String) = task {
        client.request(
            "/api/events/${event.getString("id")}/repair-offer",
            JSONObject().put("brand", "Hyundai").put("warranty", warranty)
        )
        opened = JSONObject(client.request("/api/events/${event.getString("id")}"))
        events = JSONArray(client.request("/api/events"))
    }

    private fun repairComplete(event: JSONObject, scenario: String) = task {
        client.request(
            "/api/events/${event.getString("id")}/repair-complete",
            JSONObject().put("scenario", scenario).put("technician_note", "가상 정비 후 비교 시험")
        )
        opened = JSONObject(client.request("/api/events/${event.getString("id")}"))
        events = JSONArray(client.request("/api/events"))
    }
}
