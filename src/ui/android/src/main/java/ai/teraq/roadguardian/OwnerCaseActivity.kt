package ai.teraq.roadguardian

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** 휴대폰에서 소유자가 직접 쓰는 기록. 확인을 누를 때까지 메모리에만 둔다 */
class OwnerCaseActivity : Activity() {
    private lateinit var ui: PhoneAccountUi
    private lateinit var body: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var session = ""
    private var vehicles = emptyList<String>()
    private var pending: JSONObject? = null
    private var uncertain = false
    private var saving = false
    private var interrupted = false
    private val fields = mutableListOf<EditText>()
    private val watch = object : Runnable {
        override fun run() {
            if (session.isNotEmpty() && runCatching { AuthenticatedEventsClient(this@OwnerCaseActivity).sessionFingerprint() != session }.getOrDefault(
                    true
                )
            ) clearForAccountChange()
            timer.postDelayed(this, 1000)
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (!BuildConfig.OWNER_CASES_ENABLED || packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
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
            load()
            timer.post(watch)
        }
    }

    override fun onPause() {
        interrupted = interrupted || saving || uncertain
        generation++
        timer.removeCallbacks(watch)
        session = ""
        clearDraft()
        if (::body.isInitialized) body.removeAllViews()
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

    private fun clearDraft() {
        fields.forEach { it.text?.clear() }
        fields.clear()
        pending = null
        uncertain = false
        saving = false
    }

    private fun heading(subtitle: String) {
        body.removeAllViews()
        ui.heading(body, "직접 기록 남기기", subtitle)
    }

    private fun back() {
        body.addView(ui.button("사건 목록으로 돌아가기") { finish() })
    }

    private fun clearForAccountChange() {
        generation++
        session = ""
        clearDraft()
        heading("계정 연결이 바뀌었거나 만료돼 입력 내용을 지웠습니다.")
        back()
    }

    private fun load() {
        clearDraft()
        val attempt = ++generation
        heading("기록을 남길 수 있는 차량과 계정을 확인하고 있습니다.")
        worker.execute {
            val result = runCatching {
                val client = AuthenticatedEventsClient(this)
                val features = client.capabilitiesForDisplay()
                if (!features.value.ownerCasesCreate || !client.canCreateOwnerCase()) throw IllegalStateException(
                    "not enabled"
                )
                val registered = client.registeredVehiclesForDisplay()
                if (features.sessionFingerprint != registered.sessionFingerprint) throw AccountAccessException(
                    AccountAccessException.Reason.CHANGED
                )
                require(registered.value.isNotEmpty())
                registered
            }
            runOnUiThread {
                if (isDestroyed || attempt != generation) return@runOnUiThread
                result.fold(onSuccess = { registered ->
                    vehicles = registered.value
                    session = registered.sessionFingerprint
                    form()
                }, onFailure = {
                    heading("이 계정 또는 서비스는 직접 기록 작성을 제공하지 않습니다. 연결 설정과 차량 등록을 확인해 주세요.")
                    back()
                })
            }
        }
    }

    private fun form(draft: JSONObject? = null) {
        fields.clear()
        heading("본인이 확인한 내용만 작성합니다. 자동 사고 판정이나 기관 제출은 하지 않습니다.")
        if (interrupted) body.addView(ui.notice("이전 저장 결과를 확인하지 못했습니다. 같은 내용을 다시 남기기 전에 사건 목록을 확인해 주세요."))
        body.addView(ui.text("불러온 등록 차량 · 저장할 때 접근 권한을 다시 확인합니다.", 16f, true))
        val vehicle = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@OwnerCaseActivity,
                android.R.layout.simple_spinner_dropdown_item,
                vehicles.mapIndexed { i, id -> "등록 차량 ${i + 1} · ${id.takeLast(10)}" })
        }
        draft?.optString("vehicle_id")?.let {
            vehicles.indexOf(it).takeIf { index -> index >= 0 }
                ?.let { index -> vehicle.setSelection(index) }
        }
        body.addView(vehicle, LinearLayout.LayoutParams(-1, ui.dp(56)))
        body.addView(ui.text("등록 키는 연결 서비스에서 받은 값입니다. 아래에 입력할 번호판과 자동 대조하지 않습니다.", 15f))
        val categories = listOf("road", "maintenance", "incident")
        body.addView(ui.text("자료 분류", 18f, true))
        val category = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@OwnerCaseActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("도로 관련 자료", "정비 관련 자료", "충돌 관련 자료")
            )
        }
        category.setSelection(
            categories.indexOf(draft?.optString("category") ?: "incident").coerceAtLeast(0)
        )
        body.addView(category, LinearLayout.LayoutParams(-1, ui.dp(56)))
        fun input(
            label: String,
            limit: Int,
            value: String = "",
            multiline: Boolean = false
        ): EditText {
            body.addView(ui.text(label, 17f, true))
            val field = EditText(this).apply {
                setText(value)
                textSize = 20f
                setTextColor(ui.ink)
                isSaveEnabled = false
                inputType =
                    InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                filters = arrayOf(InputFilter.LengthFilter(limit))
                if (multiline) {
                    minLines = 4
                    maxLines = 8
                }
                setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12))
            }
            fields.add(field)
            body.addView(field, LinearLayout.LayoutParams(-1, -2))
            return field
        }

        val title = input("제목 · 최대 100자", 100, draft?.optString("title") ?: "")
        val own = draft?.optJSONObject("own_vehicle")
        val plate = input("직접 입력한 차량번호 · 모르면 비워 둡니다", 40, own?.optString("plate") ?: "")
        val model = input("차종 · 모르면 비워 둡니다", 60, own?.optString("model") ?: "")
        val color = input("차량 색상 · 모르면 비워 둡니다", 30, own?.optString("color") ?: "")
        val description =
            input("직접 확인한 내용 · 최대 4000자", 4000, draft?.optString("description") ?: "", true)
        val now = CheckBox(this).apply {
            text = "기록할 사건 시각을 지금으로 입력합니다"
            textSize = 17f
            setTextColor(ui.ink)
            isChecked = draft?.isNull("reported_at") == false
        }
        body.addView(now)
        body.addView(ui.text("선택하지 않으면 시각 미기재로 남깁니다. 입력한 시각의 정확도를 확인한 것은 아닙니다.", 15f))
        body.addView(ui.notice("작성 중 화면을 나가면 입력 내용을 지웁니다. 저장한 기록은 본인 계정에만 보관하며 센서 원본·사실 확인 결과로 표시하지 않습니다."))
        body.addView(ui.button("작성 내용 확인", true) {
            if (title.text.toString().isBlank()) {
                title.error = "제목을 입력해 주세요."
                return@button
            }
            for (field in listOf(title, plate, model, color, description)) {
                val invalid = field.text.toString().codePoints().anyMatch { point ->
                    val type = Character.getType(point)
                    (type == Character.CONTROL.toInt() || type == Character.SURROGATE.toInt()) && !(field === description && point in intArrayOf(
                        10,
                        9
                    ))
                }
                if (invalid) {
                    field.error = "사용할 수 없는 제어 문자가 있습니다."
                    return@button
                }
            }
            pending = JSONObject().put("schema", "road-guardian.owner-case.v1")
                .put("request_id", UUID.randomUUID().toString())
                .put("vehicle_id", vehicles[vehicle.selectedItemPosition])
                .put("category", categories[category.selectedItemPosition])
                .put(
                    "reported_at",
                    if (now.isChecked) System.currentTimeMillis() / 1000.0 else JSONObject.NULL
                )
                .put("title", title.text.toString()).put("description", description.text.toString())
                .put(
                    "own_vehicle",
                    JSONObject().put("plate", plate.text.toString())
                        .put("model", model.text.toString()).put("color", color.text.toString())
                )
            fields.forEach { it.text?.clear() }
            fields.clear()
            uncertain = false
            preview()
        })
        back()
    }

    private fun preview(message: String = "") {
        val draft = checkNotNull(pending)
        heading(if (uncertain) "저장 결과 미확인 · 같은 요청으로 다시 확인할 수 있습니다." else "아직 저장하지 않았습니다. 입력 내용을 확인해 주세요.")
        body.addView(ui.text(draft.getString("title"), 26f, true))
        body.addView(
            ui.text(
                "불러온 등록 차량 ${vehicles.indexOf(draft.getString("vehicle_id")) + 1} · ${
                    draft.getString(
                        "vehicle_id"
                    ).takeLast(10)
                }", 17f
            )
        )
        body.addView(
            ui.text(
                "선택한 분류 · " + when (draft.getString("category")) {
                    "road" -> "도로 관련 자료"
                    "maintenance" -> "정비 관련 자료"
                    else -> "충돌 관련 자료"
                }, 17f
            )
        )
        val own = draft.getJSONObject("own_vehicle")
        body.addView(
            ui.notice(
                listOf(
                    own.getString("plate"),
                    own.getString("model"),
                    own.getString("color")
                ).map { it.ifBlank { "미기재" } }.joinToString(" · ")
            )
        )
        body.addView(ui.text(draft.getString("description").ifBlank { "작성한 설명 없음" }))
        body.addView(
            ui.text(
                if (draft.isNull("reported_at")) "사건 시각 미기재" else "사건 시각을 작성 시점으로 입력했습니다.",
                16f
            )
        )
        body.addView(ui.notice("본인이 작성한 내용으로 보관합니다. 차량 정보·현장 사실·책임을 인증하거나 기관에 제출하는 기능이 아닙니다."))
        if (message.isNotEmpty()) body.addView(ui.notice(message))
        body.addView(
            ui.button(
                if (uncertain) "같은 내용으로 저장 결과 재확인" else "이 내용으로 기록 남기기",
                true
            ) { save() })
        if (!uncertain) body.addView(ui.button("입력 내용 수정") { form(draft) })
        back()
    }

    private fun save() {
        val draft = checkNotNull(pending)
        val expected = session
        val attempt = ++generation
        saving = true
        heading("차량 접근 권한과 저장 결과를 확인하고 있습니다.")
        worker.execute {
            val result = runCatching {
                val client = AuthenticatedEventsClient(this)
                if (client.sessionFingerprint() != expected) throw AccountAccessException(
                    AccountAccessException.Reason.CHANGED
                )
                require(client.capabilitiesForDisplay().value.ownerCasesCreate)
                if (draft.getString("vehicle_id") !in client.registeredVehiclesForDisplay().value) throw AccountAccessException(
                    AccountAccessException.Reason.NOT_FOUND
                )
                client.createOwnerCaseForDisplay(draft, expected)
            }
            runOnUiThread {
                if (isDestroyed || attempt != generation) return@runOnUiThread
                saving = false
                result.fold(onSuccess = { received ->
                    pending = null
                    interrupted = false
                    startActivity(
                        Intent(
                            this,
                            AccountEventsActivity::class.java
                        ).putExtra("notification_event_id", received.value.getString("id"))
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                    finish()
                }, onFailure = { failure ->
                    val reason = (failure as? AccountAccessException)?.reason
                    if (reason in setOf(
                            AccountAccessException.Reason.SIGN_IN,
                            AccountAccessException.Reason.EXPIRED,
                            AccountAccessException.Reason.CHANGED
                        )
                    ) {
                        clearForAccountChange()
                        return@fold
                    }
                    if (reason in setOf(
                            AccountAccessException.Reason.FORBIDDEN,
                            AccountAccessException.Reason.CONFLICT,
                            AccountAccessException.Reason.NOT_FOUND
                        )
                    ) {
                        pending = null
                        heading("차량 권한·같은 요청의 내용 또는 보관 한도를 확인해 주세요. 기록을 자동으로 덮어쓰지 않았습니다.")
                        back()
                    } else {
                        uncertain = true
                        preview("저장 성공 여부를 확인하지 못했습니다. 같은 내용을 다시 확인하면 중복 저장을 방지할 수 있습니다.")
                    }
                })
            }
        }
    }
}
