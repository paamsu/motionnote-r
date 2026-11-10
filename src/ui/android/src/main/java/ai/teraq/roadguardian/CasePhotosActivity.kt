package ai.teraq.roadguardian

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputFilter
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** 휴대폰 사진 선택, 미리보기, 확인, 표시 화면. 자동 업로드와 임시 파일은 없다 */
class CasePhotosActivity : Activity() {
    private data class Loaded(
        val records: AuthenticatedEventsClient.DisplayResult<List<CasePhoto>>,
        val writable: Boolean,
        val photos: List<Pair<CasePhoto, ByteArray>>,
        val label: String,
        val exportable: Boolean
    )

    private lateinit var ui: PhoneAccountUi
    private lateinit var body: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Handler(Looper.getMainLooper())
    private var eventId = ""
    private var session = ""
    private var selectionSession = ""
    private var generation = 0L
    private var choosing = false
    private var importing = false
    private var saving = false
    private var uncertain = false
    private var interrupted = false
    private var pending: JSONObject? = null
    private val bitmaps = mutableListOf<Bitmap>()
    private var lease: AccountDisplayLease? = null
    private var recordLabel = ""
    private val watch = object : Runnable {
        override fun run() {
            if (session.isNotEmpty()) {
                val changed =
                    runCatching { AuthenticatedEventsClient(this@CasePhotosActivity).sessionFingerprint() != session }.getOrDefault(
                        true
                    )
                if (changed || lease?.valid(
                        System.currentTimeMillis(),
                        android.os.SystemClock.elapsedRealtime()
                    ) == false
                ) {
                    generation++
                    session = ""
                    clear()
                    heading("계정 연결 또는 표시 기한을 다시 확인해 주세요.")
                    back()
                }
            }
            timer.postDelayed(this, 1000)
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (!BuildConfig.CASE_PHOTOS_ENABLED || packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            finish()
            return
        }
        eventId =
            runCatching { AccountEventRoute.checked(intent.getStringExtra("event_id")) }.getOrNull()
                ?: run {
                    finish()
                    return
                }
        interrupted = saved?.getBoolean("saving") == true
        ui = PhoneAccountUi(this)
        body = ui.install()
        body.isSaveFromParentEnabled = false
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) {
            if (body.childCount == 0 && !choosing && !importing) load()
            timer.post(watch)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::ui.isInitialized) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val mask =
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(mask, mask)
            }
        }
    }

    override fun onPause() {
        interrupted = interrupted || saving || uncertain
        generation++
        timer.removeCallbacks(watch)
        session = ""
        clear()
        importing = false
        if (!choosing) selectionSession = ""
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("saving", saving || uncertain || interrupted)
        super.onSaveInstanceState(out)
    }

    private fun clear() {
        if (::body.isInitialized) body.removeAllViews()
        bitmaps.forEach { it.recycle() }
        bitmaps.clear()
        pending = null
        saving = false
        uncertain = false
        lease = null
        recordLabel = ""
    }

    private fun heading(subtitle: String) {
        body.removeAllViews()
        bitmaps.forEach { it.recycle() }
        bitmaps.clear()
        ui.evidenceHeading(body, "기록에 첨부한 사진", subtitle)
        if (recordLabel.isNotEmpty()) body.addView(ui.text(recordLabel, 18f, true))
    }

    private fun caseLabel(event: JSONObject): String {
        val data = event.getJSONObject("data")
        val plate = data.optJSONObject("context")?.optJSONObject("own_vehicle")?.optString("plate")
            .orEmpty()
        return data.getString("title") + "\n입력한 차량 · " + plate.ifBlank { "미기재" }
    }

    private fun back() {
        body.addView(ui.button("사건 상세로 돌아가기") { finish() })
    }

    private fun load() {
        clear()
        val attempt = ++generation
        heading("차량 접근 권한과 본인 계정의 사진을 확인하고 있습니다.")
        worker.execute {
            val result = runCatching {
                val client = AuthenticatedEventsClient(this)
                val features = client.capabilitiesForDisplay()
                if (!features.value.photosRead) throw AccountAccessException(AccountAccessException.Reason.NOT_SUPPORTED)
                val event = client.eventForDisplay(eventId)
                if (event.value.optString("source") != "owner_reported" || !event.value.optBoolean("evidence_valid")) throw AccountAccessException(
                    AccountAccessException.Reason.NOT_FOUND
                )
                val records = client.photosForDisplay(eventId)
                require(features.sessionFingerprint == records.sessionFingerprint && event.sessionFingerprint == records.sessionFingerprint)
                val photos = records.value.map { photo ->
                    val fetched = client.photoForDisplay(photo)
                    require(fetched.sessionFingerprint == records.sessionFingerprint)
                    photo to fetched.value
                }
                Loaded(
                    records,
                    features.value.photosWrite && client.canAttachPhotos(),
                    photos,
                    caseLabel(event.value),
                    features.value.photosExport
                )
            }
            runOnUiThread {
                if (isDestroyed || attempt != generation) return@runOnUiThread
                result.fold(onSuccess = { loaded ->
                    val records = loaded.records
                    if (runCatching { AuthenticatedEventsClient(this).sessionFingerprint() != records.sessionFingerprint }.getOrDefault(
                            true
                        )
                    ) {
                        clear()
                        heading("계정 연결이 바뀌어 사진을 표시하지 않았습니다.")
                        back()
                        return@fold
                    }
                    if (!records.lease.valid(
                            System.currentTimeMillis(),
                            android.os.SystemClock.elapsedRealtime()
                        )
                    ) {
                        heading("표시 기한이 지났습니다. 다시 확인해 주세요.")
                        back()
                        return@fold
                    }
                    session = records.sessionFingerprint
                    lease = records.lease
                    recordLabel = loaded.label
                    heading("본인 첨부 · 촬영 시점·내용 미확인")
                    if (interrupted) body.addView(ui.notice("이전 보관 결과가 불분명합니다. 같은 사진을 다시 붙이기 전에 아래 목록을 확인해 주세요."))
                    body.addView(ui.text("보관한 사진 ${loaded.photos.size}/4", 22f, true))
                    if (loaded.photos.size >= 2) body.addView(
                        CasePhotoComparison(
                            ui,
                            loaded.photos
                        ) {
                            !isFinishing && !isDestroyed && session.isNotEmpty() && lease?.valid(
                                System.currentTimeMillis(),
                                android.os.SystemClock.elapsedRealtime()
                            ) == true &&
                                    runCatching { AuthenticatedEventsClient(this).sessionFingerprint() == session }.getOrDefault(
                                        false
                                    )
                        }) else loaded.photos.forEachIndexed { index, (photo, raw) ->
                        body.addView(ui.text("사진 ${index + 1}", 18f, true))
                        showImage(raw)
                        body.addView(ui.text(photo.caption.ifBlank { "작성한 설명 없음" }, 18f))
                    }
                    if (loaded.photos.isEmpty()) body.addView(ui.notice("아직 첨부한 사진이 없습니다."))
                    if (loaded.writable && loaded.photos.size < 4) body.addView(
                        ui.button(
                            "사진 선택 후 확인",
                            true
                        ) { choose() })
                    if (!loaded.writable) body.addView(ui.text("현재 계정은 사진 조회만 허용합니다.", 16f))
                    ui.disclosure(body, "사진 처리 안내", LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            ui.text(
                                "사진의 위치·촬영 시각 등 파일 메타데이터는 제외합니다. " + if (loaded.exportable) "이 서비스는 사건 자료 묶음에 첨부 사진 포함을 지원합니다." else "이 서비스는 첨부 사진의 묶음 저장을 제공하지 않습니다.",
                                15f
                            )
                        )
                    })
                    body.addView(ui.button("사진 목록 다시 확인") { load() })
                    back()
                }, onFailure = { failure ->
                    heading("사진을 표시하지 못했습니다.")
                    body.addView(
                        ui.notice(
                            (failure as? AccountAccessException)?.message
                                ?: "연결과 차량 접근 권한을 확인해 주세요."
                        )
                    )
                    back()
                })
            }
        }
    }

    private fun showImage(raw: ByteArray) {
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size, options))
        bitmaps.add(bitmap)
        body.addView(ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "사용자가 첨부한 변환 사진 · 촬영 시점과 내용 미확인"
        }, LinearLayout.LayoutParams(-1, ui.dp(230)).apply { bottomMargin = ui.dp(12) })
    }

    private fun choose() {
        selectionSession = session
        choosing = true
        try {
            val picker =
                if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    type = "image/*"
                }
                else Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            startActivityForResult(picker, 82)
        } catch (_: Exception) {
            choosing = false
            selectionSession = ""
            body.addView(ui.notice("사진 선택 창을 열지 못했습니다."))
        }
    }

    @Deprecated("Legacy Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 82) return
        choosing = false
        val expected = selectionSession
        selectionSession = ""
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || expected.isEmpty()) {
            load()
            return
        }
        val attempt = ++generation
        importing = true
        session = expected
        heading("선택한 사진을 전송용으로 변환하고 있습니다. 아직 저장하지 않았습니다.")
        worker.execute {
            val result = runCatching {
                val client = AuthenticatedEventsClient(this)
                if (client.sessionFingerprint() != expected) throw AccountAccessException(
                    AccountAccessException.Reason.CHANGED
                )
                val bytes = CasePhotoDraft.fromUri(this, uri)
                val event = client.eventForDisplay(eventId)
                require(
                    event.sessionFingerprint == expected && event.value.optString("source") == "owner_reported" && event.value.optBoolean(
                        "evidence_valid"
                    )
                )
                if (client.sessionFingerprint() != expected) throw AccountAccessException(
                    AccountAccessException.Reason.CHANGED
                )
                bytes to caseLabel(event.value)
            }
            runOnUiThread {
                if (isDestroyed || attempt != generation) return@runOnUiThread
                importing = false
                result.fold(onSuccess = { prepared ->
                    if (runCatching { AuthenticatedEventsClient(this).sessionFingerprint() != expected }.getOrDefault(
                            true
                        )
                    ) {
                        clear()
                        heading("계정 연결이 바뀌어 선택한 사진을 지웠습니다.")
                        back()
                        return@fold
                    }
                    val raw = prepared.first
                    recordLabel = prepared.second
                    pending = JSONObject().put("schema", "road-guardian.case-photo-upload.v1")
                        .put("request_id", UUID.randomUUID().toString())
                        .put("caption", "").put(
                            "image_base64",
                            android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
                        )
                    preview()
                }, onFailure = { failure ->
                    clear()
                    heading("사진을 준비하지 못했습니다.")
                    body.addView(
                        ui.notice(
                            (failure as? AccountAccessException)?.message
                                ?: "12MiB 이하의 다른 사진을 선택해 주세요."
                        )
                    )
                    back()
                })
            }
        }
    }

    private fun preview() {
        val draft = checkNotNull(pending)
        heading(if (uncertain) "보관 결과 미확인 · 같은 요청으로 확인" else "미리보기 · 아직 보관하지 않음")
        showImage(
            android.util.Base64.decode(
                draft.getString("image_base64"),
                android.util.Base64.DEFAULT
            )
        )
        val caption = EditText(this).apply {
            hint = "사진 설명 · 선택 사항"
            setText(draft.getString("caption"))
            textSize = 18f
            typeface = PleosTypography.face()
            setTextColor(ui.ink)
            setHintTextColor(ui.muted)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ui.muted)
            filters = arrayOf(InputFilter.LengthFilter(200))
            isSaveEnabled = false
            isEnabled = !uncertain
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        body.addView(caption)
        val contextForm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val zone = java.time.ZoneId.systemDefault()
        fun contextField(hintText: String, initial: String): EditText {
            contextForm.addView(ui.text(hintText.substringBefore(" ·"), 16f, true))
            return EditText(this).apply {
                hint = hintText
                setText(initial)
                textSize = 18f
                typeface = PleosTypography.face()
                setTextColor(ui.ink)
                setHintTextColor(ui.muted)
                filters = arrayOf(InputFilter.LengthFilter(80))
                isSaveEnabled = false
                isEnabled = !uncertain
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                contextForm.addView(this)
            }
        }

        val previous = draft.optJSONObject("capture_context")
        val localTime = previous?.optString("captured_at")?.let {
            java.time.OffsetDateTime.parse(it).atZoneSameInstant(zone)
                .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))
        }.orEmpty()
        contextForm.addView(ui.text("직접 작성한 촬영 맥락 · 시간대 ${zone.id}\n세 항목을 모두 입력하거나 비워 두세요.", 16f))
        val captured = contextField("촬영 시각 · 예: 2026-11-02 14:30:00", localTime)
        val place = contextField("장소 · 예: 지하 2층 B12", previous?.optString("place_id").orEmpty())
        val viewpoint =
            contextField("시야 · 예: 기둥을 바라본 차량 앞쪽", previous?.optString("viewpoint_id").orEmpty())
        val contextError = ui.text("", 16f).apply { visibility = View.GONE }
        contextForm.addView(contextError)
        ui.disclosure(body, "촬영 맥락 작성 · 선택", contextForm)

        body.addView(ui.text("변환 사진을 본인 계정에 보관합니다. 촬영 시점·내용은 미확인입니다.", 16f))
        body.addView(ui.button(if (uncertain) "같은 요청으로 보관 결과 확인" else "이 사진을 기록에 보관", true) {
            if (!uncertain) {
                val text = caption.text.toString()
                if (text.codePoints().anyMatch {
                        Character.getType(it) in intArrayOf(
                            Character.CONTROL.toInt(),
                            Character.SURROGATE.toInt()
                        )
                    }) {
                    caption.error = "사용할 수 없는 문자가 있습니다."
                    return@button
                }
                val values = listOf(
                    captured.text.toString(),
                    place.text.toString(),
                    viewpoint.text.toString()
                )
                if (values.all { it.isBlank() }) draft.remove("capture_context") else {
                    val context = runCatching {
                        require(values.all { it.isNotBlank() })
                        val formatter =
                            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                                .withResolverStyle(java.time.format.ResolverStyle.STRICT)
                        val local = java.time.LocalDateTime.parse(values[0], formatter)
                        val offsets = zone.rules.getValidOffsets(local)
                        require(offsets.size == 1)
                        val timestamp = local.atOffset(offsets.single())
                            .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX"))
                        PhotoCaptureContext.parse(
                            JSONObject().put("source", "owner_reported")
                                .put("captured_at", timestamp).put("place_id", values[1])
                                .put("viewpoint_id", values[2])
                        )
                    }.getOrNull()
                    if (context == null) {
                        contextError.text =
                            "촬영 시각·장소·시야를 확인해 주세요. 시각은 2026-11-02 14:30:00 형식으로 입력합니다."
                        contextError.visibility = View.VISIBLE
                        captured.error = "촬영 맥락 확인 필요"
                        contextError.requestRectangleOnScreen(
                            android.graphics.Rect(
                                0,
                                0,
                                contextError.width,
                                contextError.height
                            ), true
                        )
                        return@button
                    }
                    contextError.visibility = View.GONE
                    draft.put("capture_context", context.json())
                }
                draft.put("caption", text)
            }
            save()
        })
        back()
    }

    private fun save() {
        val draft = checkNotNull(pending)
        val expected = session
        val attempt = ++generation
        saving = true
        heading("차량 접근 권한과 사진 보관 결과를 확인하고 있습니다.")
        worker.execute {
            val result = runCatching {
                val client = AuthenticatedEventsClient(this)
                if (client.sessionFingerprint() != expected) throw AccountAccessException(
                    AccountAccessException.Reason.CHANGED
                )
                require(client.capabilitiesForDisplay().value.photosWrite)
                client.savePhotoForDisplay(eventId, draft, expected)
            }
            runOnUiThread {
                if (isDestroyed || attempt != generation) return@runOnUiThread
                saving = false
                result.fold(onSuccess = {
                    interrupted = false
                    pending = null
                    load()
                }, onFailure = { failure ->
                    val reason = (failure as? AccountAccessException)?.reason
                    if (reason in setOf(
                            AccountAccessException.Reason.SIGN_IN,
                            AccountAccessException.Reason.EXPIRED,
                            AccountAccessException.Reason.CHANGED,
                            AccountAccessException.Reason.NOT_FOUND,
                            AccountAccessException.Reason.FORBIDDEN,
                            AccountAccessException.Reason.CONFLICT
                        )
                    ) {
                        clear()
                        heading("계정·차량 권한 또는 보관 한도를 다시 확인해 주세요.")
                        back()
                    } else {
                        uncertain = true
                        preview()
                    }
                })
            }
        }
    }
}
