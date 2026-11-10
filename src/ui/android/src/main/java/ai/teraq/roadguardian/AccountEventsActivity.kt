package ai.teraq.roadguardian

import android.app.Activity
import android.os.Bundle
import android.content.pm.PackageManager
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import android.os.Handler
import android.os.Looper

/** 계정 사건 조회 화면. 개인 사건은 시연용 상세 화면으로 넘기지 않는다 */
class AccountEventsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0L
    private var currentCursor: String? = null
    private var currentCategory = "all"
    private var selectedEventId: String? = null
    private var invalidNotification = false
    private val previousPages = mutableListOf<String?>()
    private var pendingExportId: String? = null
    private var exporting = false
    private var exportMessage: String? = null
    private var exportTask: FutureTask<String>? = null
    private val statusHandler = Handler(Looper.getMainLooper())
    private var displayLease: AccountDisplayLease? = null
    private val checkDisplay = object : Runnable {
        override fun run() {
            val lease = displayLease
            if (lease != null && !lease.valid(
                    System.currentTimeMillis(),
                    android.os.SystemClock.elapsedRealtime()
                )
            ) expireDisplay()
            statusHandler.postDelayed(this, 1000)
        }
    }

    private fun expireDisplay() {
        displayLease = null
        generation++
        body.removeAllViews()
        previousPages.clear()
        currentCursor = null
        currentCategory = "all"
        ui.heading(body, "계정 연결 확인", "로그인 시간이 만료되어 기록을 화면에서 지웠습니다.")
        body.addView(ui.button("계정 연결로 돌아가기", true) { finish() })
    }

    private val checkExport = object : Runnable {
        override fun run() {
            val task = exportTask ?: return
            if (task.isDone) {
                exportMessage = try {
                    task.get()
                } catch (_: Exception) {
                    "저장 결과를 확인할 수 없습니다. 선택한 위치의 파일을 확인해 주세요."
                }
                exporting = false
                exportTask = null
                showExportStatus()
            } else statusHandler.postDelayed(this, 250)
        }
    }
    private lateinit var body: LinearLayout
    private lateinit var ui: PhoneAccountUi
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            finish()
            return
        }
        ui = PhoneAccountUi(this)
        body = ui.install()
        if (saved != null) {
            selectedEventId = saved.getString("selected_event")
            invalidNotification = saved.getBoolean("invalid_notification")
        } else readNotificationRoute()
        currentCategory = saved?.getString("category")
            ?.takeIf { it in setOf("all", "road", "maintenance", "incident") } ?: "all"
        pendingExportId = saved?.getString("pending_export")
        exportMessage = saved?.getString("export_message")
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val retained = lastNonConfigurationInstance as? FutureTask<String>
        exportTask = retained
        exporting = retained != null
        if (retained == null && saved?.getBoolean("export_in_progress") == true)
            exportMessage = "화면이 다시 열려 저장 결과를 확인할 수 없습니다. 선택한 위치의 파일을 확인해 주세요."
    }

    private fun readNotificationRoute() {
        invalidNotification = false
        selectedEventId = null
        if (intent.hasExtra("notification_event_id")) try {
            selectedEventId =
                AccountEventRoute.checked(intent.getStringExtra("notification_event_id"))
        } catch (_: IllegalArgumentException) {
            invalidNotification = true
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        generation++
        readNotificationRoute()
    }

    @Deprecated("Framework retained instance")
    override fun onRetainNonConfigurationInstance(): Any? = exportTask
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("category", currentCategory)
        out.putString("pending_export", pendingExportId)
        out.putString("export_message", exportMessage)
        out.putBoolean("export_in_progress", exporting)
        out.putString("selected_event", selectedEventId)
        out.putBoolean("invalid_notification", invalidNotification)
        super.onSaveInstanceState(out)
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) {
            statusHandler.removeCallbacks(checkDisplay)
            statusHandler.post(checkDisplay)
            if (exporting || exportMessage != null) showExportStatus()
            else if (invalidNotification) {
                body.removeAllViews()
                ui.heading(body, "알림 확인", "알림의 사건 번호를 확인하지 못했습니다.")
                body.addView(ui.button("사건 목록 확인", true) {
                    invalidNotification = false
                    load()
                })
            } else selectedEventId?.let { detail(it) } ?: load(currentCursor)
            if (exportTask != null) {
                statusHandler.removeCallbacks(checkExport)
                statusHandler.post(checkExport)
            }
        }
    }

    override fun onPause() {
        statusHandler.removeCallbacks(checkExport)
        statusHandler.removeCallbacks(checkDisplay)
        displayLease = null
        generation++
        if (::body.isInitialized) body.removeAllViews()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun load(cursor: String? = null, category: String = currentCategory) {
        if (category != currentCategory) previousPages.clear()
        currentCategory = category
        selectedEventId = null
        displayLease = null
        currentCursor = cursor
        val attempt = ++generation
        body.removeAllViews()
        ui.heading(body, "사건 근거", "이 계정으로 조회할 수 있는 기록입니다.")
        body.addView(ui.notice("차량 권한을 확인하고 있습니다."))
        worker.execute {
            val result = runCatching {
                val client = AuthenticatedEventsClient(this)
                val page = client.pageForDisplay(cursor, category)
                val features =
                    if (BuildConfig.OWNER_CASES_ENABLED) runCatching { client.capabilitiesForDisplay() }.getOrNull() else null
                if (features != null && features.sessionFingerprint != page.sessionFingerprint) throw AccountAccessException(
                    AccountAccessException.Reason.CHANGED
                )
                Pair(page, features?.value?.ownerCasesCreate == true)
            }
            runOnUiThread {
                if (isDestroyed || attempt != generation) return@runOnUiThread
                body.removeAllViews()
                ui.heading(body, "사건 근거", "이 계정으로 조회할 수 있는 기록입니다.")
                var retry = true
                result.fold(onSuccess = { bundle ->
                    val received = bundle.first
                    if (!received.lease.valid(
                            System.currentTimeMillis(),
                            android.os.SystemClock.elapsedRealtime()
                        )
                    ) {
                        expireDisplay()
                        return@runOnUiThread
                    }
                    displayLease = received.lease
                    val page = received.value
                    if (bundle.second) body.addView(ui.button("직접 기록 남기기", true) {
                        startActivity(
                            android.content.Intent(this, OwnerCaseActivity::class.java)
                        )
                    })
                    CaseWorkspace.render(ui, body, page.events, currentCategory, {
                        previousPages.clear()
                        load(null, it)
                    }) { detail(it) }
                    if (page.next != null) body.addView(ui.button("이전 시기의 기록 더 보기", true) {
                        previousPages.add(cursor)
                        load(page.next)
                    })
                }, onFailure = { failure ->
                    val issue = failure as? AccountAccessException ?: AccountAccessException(
                        AccountAccessException.Reason.UNAVAILABLE
                    )
                    retry = issue.canRetry()
                    body.addView(ui.notice(issue.message ?: "기록을 확인하지 못했습니다."))
                })
                if (previousPages.isNotEmpty()) body.addView(ui.button("앞 목록으로") {
                    load(previousPages.removeAt(previousPages.lastIndex))
                })
                if (retry) body.addView(ui.button("최신 기록부터 확인") {
                    previousPages.clear()
                    load()
                })
                body.addView(ui.button("계정 연결로 돌아가기") { finish() })
            }
        }
    }

    private fun detail(id: String) {
        selectedEventId = id
        displayLease = null
        val attempt = ++generation
        body.removeAllViews()
        ui.heading(body, "사건 상세", "차량 접근 권한을 다시 확인하고 있습니다.")
        worker.execute {
            val result = runCatching {
                val client = AuthenticatedEventsClient(this)
                val received = client.eventForDisplay(id)
                val features =
                    if (BuildConfig.REVIEW_NOTES_ENABLED || BuildConfig.CASE_PHOTOS_ENABLED || BuildConfig.POWERTRAIN_ENABLED || BuildConfig.MEASUREMENTS_ENABLED) runCatching { client.capabilitiesForDisplay() }.getOrNull()?.value else null
                if (client.sessionFingerprint() != received.sessionFingerprint) throw AccountAccessException(
                    AccountAccessException.Reason.CHANGED
                )
                received to features
            }
            runOnUiThread {
                if (isDestroyed || attempt != generation) return@runOnUiThread
                result.fold(onSuccess = { bundle ->
                    val received = bundle.first
                    if (!received.lease.valid(
                            System.currentTimeMillis(),
                            android.os.SystemClock.elapsedRealtime()
                        )
                    ) {
                        expireDisplay()
                        return@runOnUiThread
                    }
                    displayLease = received.lease
                    val event = received.value
                    if (EvidenceScope.category(event) == null) {
                        body.removeAllViews()
                        ui.heading(body, "알림의 기록 확인", "이 기록은 현재 제공하는 근거 정리 범위에 해당하지 않습니다.")
                        body.addView(ui.button("사건 근거 목록", true) { load(null) })
                        return@runOnUiThread
                    }
                    AccountEventDetail.render(ui, body, event)
                    if (BuildConfig.MEASUREMENTS_ENABLED && bundle.second?.measurementsRead == true && event.optString(
                            "source"
                        ) == "owner_reported" && EvidenceScope.category(event) == "maintenance" && event.optBoolean(
                            "evidence_valid"
                        )
                    )
                        body.addView(ui.button("앞·뒤 측정 기록 비교") {
                            startActivity(
                                android.content.Intent(
                                    this,
                                    MeasurementActivity::class.java
                                ).putExtra("event_id", id)
                            )
                        })
                    if (BuildConfig.POWERTRAIN_ENABLED && bundle.second?.powertrainRead == true && event.optString(
                            "source"
                        ) == "owner_reported" && EvidenceScope.category(event) == "maintenance" && event.optBoolean(
                            "evidence_valid"
                        )
                    )
                        body.addView(ui.button("정비 기록의 Pleos 자료 조회") {
                            startActivity(
                                android.content.Intent(
                                    this,
                                    PowertrainActivity::class.java
                                ).putExtra("event_id", id)
                            )
                        })
                    if (BuildConfig.CASE_PHOTOS_ENABLED && event.optString("source") == "owner_reported" && event.optBoolean(
                            "evidence_valid"
                        ) && bundle.second?.photosRead == true
                    )
                        body.addView(ui.button("기록에 첨부한 사진") {
                            startActivity(
                                android.content.Intent(
                                    this,
                                    CasePhotosActivity::class.java
                                ).putExtra("event_id", id)
                            )
                        })
                    if (BuildConfig.SDK_OBSERVATIONS_ENABLED && event.optBoolean("evidence_valid"))
                        body.addView(
                            SdkObservationsPanel(
                                this,
                                ui,
                                id,
                                event.optString("source") == "synthetic",
                                { !isFinishing && !isDestroyed })
                        )
                    if (BuildConfig.REVIEW_NOTES_ENABLED && bundle.second?.notesRead == true) {
                        body.addView(ui.button("내 확인 메모") {
                            startActivity(
                                android.content.Intent(
                                    this,
                                    ReviewNoteActivity::class.java
                                ).putExtra("event_id", id)
                            )
                        })
                        if (event.optBoolean(
                                "export_available",
                                true
                            )
                        ) body.addView(ui.text("증거 묶음을 저장하면 이 계정의 확인 메모도 함께 담깁니다.", 15f))
                    }
                    if (event.optJSONObject("video")?.optBoolean("integrity_valid") == true)
                        body.addView(ui.button("근거 영상 확인") {
                            startActivity(
                                android.content.Intent(
                                    this,
                                    AccountVideoActivity::class.java
                                ).putExtra("event_id", id)
                            )
                        })
                    if (event.optBoolean(
                            "export_available",
                            true
                        )
                    ) body.addView(ui.button("증거 묶음 저장") {
                        pendingExportId = id
                        try {
                            startActivityForResult(
                                android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                                    .apply {
                                        type = "application/zip"
                                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                        val label = when (event.optString("source")) {
                                            "synthetic" -> "가상증거"
                                            "owner_reported" -> "작성기록"
                                            else -> "증거"
                                        }
                                        putExtra(
                                            android.content.Intent.EXTRA_TITLE,
                                            "R_${label}_${id.take(8)}.zip"
                                        )
                                    }, 81
                            )
                        } catch (_: Exception) {
                            pendingExportId = null
                            exportMessage = "저장 위치 선택 창을 열지 못했습니다."
                            showExportStatus()
                        }
                    }) else body.addView(ui.text("이 기록은 묶음 저장을 제공하지 않습니다.", 15f))
                }, onFailure = { failure ->
                    body.removeAllViews()
                    ui.heading(body, "사건 상세", "기록을 표시할 수 없습니다.")
                    val issue = failure as? AccountAccessException ?: AccountAccessException(
                        AccountAccessException.Reason.UNAVAILABLE
                    )
                    body.addView(ui.notice(issue.message ?: "기록을 확인하지 못했습니다."))
                    if (issue.reason in setOf(
                            AccountAccessException.Reason.SIGN_IN,
                            AccountAccessException.Reason.EXPIRED,
                            AccountAccessException.Reason.NOT_CONFIGURED
                        )
                    )
                        body.addView(ui.button("계정 연결 확인", true) {
                            startActivity(
                                android.content.Intent(this, LoginActivity::class.java)
                                    .putExtra("return_event_id", id)
                            )
                        })
                })
                body.addView(ui.button("사건 목록으로", true) { load(currentCursor) })
            }
        }
    }

    private fun showExportStatus() {
        displayLease = null
        generation++
        body.removeAllViews()
        ui.heading(body, "증거 묶음 저장", "선택한 위치에 보관 자료를 저장합니다. 직접 작성 내용과 센서 자료는 출처를 구분합니다.")
        body.addView(
            ui.notice(
                if (exporting) "차량 권한과 묶음 안의 원본·파일 목록을 확인한 뒤 저장하고 있습니다." else exportMessage
                    ?: "저장이 취소됐습니다."
            )
        )
        if (!exporting) body.addView(ui.button("사건 목록으로", true) {
            exportMessage = null
            load(currentCursor)
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
        exporting = true
        exportMessage = null
        showExportStatus()
        val appContext = applicationContext
        val task = FutureTask<String> {
            try {
                val payload = AuthenticatedEventsClient(appContext).evidencePackage(id)
                try {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val output = appContext.contentResolver.openOutputStream(uri, "wt")
                        ?: throw java.io.IOException()
                    output.use { destination ->
                        payload.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                                val count = input.read(buffer)
                                if (count < 0) break
                                destination.write(buffer, 0, count)
                            }
                        }
                    }
                } finally {
                    payload.delete()
                }
                "원본과 파일 목록의 일치를 확인한 묶음을 저장했습니다. 현장 사실 확인이나 기관 제출을 뜻하지 않습니다."
            } catch (failure: Exception) {
                val issue = failure as? AccountAccessException
                (issue?.message
                    ?: "저장하지 못했습니다. 연결과 저장 공간을 확인해 주세요.") + " 선택한 위치에 빈 파일이나 일부만 저장된 파일이 남을 수 있습니다."
            }
        }
        exportTask = task
        Thread(task, "account-evidence-export").start()
    }

}
