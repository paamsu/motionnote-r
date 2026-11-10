package ai.teraq.roadguardian

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import java.util.UUID
import java.util.concurrent.Executors

/** 휴대폰 개인 메모. 작성 중인 내용은 저장 상태, 로그, 센서 원본에 넣지 않는다 */
class ReviewNoteActivity : Activity() {
    private lateinit var ui: PhoneAccountUi
    private lateinit var body: LinearLayout
    private lateinit var id: String
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var lease: AccountDisplayLease? = null
    private var session = ""
    private var editor: EditText? = null
    private val expiry = object : Runnable {
        override fun run() {
            if (lease != null && (!lease!!.valid(
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime()
                ) ||
                        runCatching { AuthenticatedEventsClient(this@ReviewNoteActivity).sessionFingerprint() != session }.getOrDefault(
                            true
                        ))
            ) expired()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (!BuildConfig.REVIEW_NOTES_ENABLED || packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            finish()
            return
        }
        id = try {
            AccountEventRoute.checked(intent.getStringExtra("event_id"))
        } catch (_: Exception) {
            finish()
            return
        }
        ui = PhoneAccountUi(this)
        body = ui.install()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) {
            load()
            handler.post(expiry)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(expiry)
        generation++
        lease = null
        session = ""
        editor?.text?.clear()
        editor = null
        if (::body.isInitialized) body.removeAllViews()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun heading(message: String) {
        body.removeAllViews()
        ui.heading(body, "내 확인 메모", message)
    }

    private fun expired() {
        generation++
        lease = null
        session = ""
        editor?.text?.clear()
        editor = null
        heading("계정 연결이 바뀌었거나 시간이 만료돼 메모를 화면에서 지웠습니다.")
        body.addView(ui.button("사건 화면으로 돌아가기", true) { finish() })
    }

    private fun load() {
        val attempt = ++generation
        lease = null
        editor?.text?.clear()
        editor = null
        heading("본인 계정의 메모를 확인하고 있습니다.")
        worker.execute {
            val result = runCatching { AuthenticatedEventsClient(this).noteForDisplay(id) }
            runOnUiThread {
                if (isDestroyed || attempt != generation) return@runOnUiThread
                result.fold(onSuccess = { render(it) }, onFailure = {
                    heading((it as? AccountAccessException)?.message ?: "메모를 불러오지 못했습니다.")
                    body.addView(ui.button("다시 조회", true) { load() })
                    body.addView(ui.button("돌아가기") { finish() })
                })
            }
        }
    }

    private fun render(
        received: AuthenticatedEventsClient.DisplayResult<PrivateReviewNote>,
        message: String = ""
    ) {
        if (!received.lease.valid(System.currentTimeMillis(), SystemClock.elapsedRealtime())) {
            expired()
            return
        }
        lease = received.lease
        session = received.sessionFingerprint
        val note = received.value
        heading(if (note.revision == 0) "아직 저장한 메모가 없습니다." else "저장한 메모 · ${note.revision}번째 수정")
        body.addView(ui.notice("이 계정에서만 보입니다. 묶음 저장을 제공하는 기록에서는 메모도 함께 담깁니다. 차량 원본이나 확인된 사실을 바꾸지 않습니다."))
        val field = EditText(this).apply {
            setText(note.text)
            hint = "직접 확인한 내용과 확인이 필요한 내용을 적어 주세요."
            textSize = 20f
            setTextColor(ui.ink)
            setHintTextColor(ui.muted)
            minLines = 4
            maxLines = 10
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(2000))
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        editor = field
        body.addView(field, LinearLayout.LayoutParams(-1, -2))
        body.addView(ui.text("최대 2000자 · 저장 전 화면을 나가면 작성 내용이 사라집니다.", 15f))
        body.addView(ui.text("저장한 이전 수정 기록은 서버에 보관됩니다.", 15f))
        if (message.isNotBlank()) body.addView(ui.notice(message))
        val allowed =
            runCatching { AuthenticatedEventsClient(this).canReviewNotes() }.getOrDefault(false)
        field.isEnabled = allowed
        if (allowed) body.addView(ui.button("메모 저장", true) {
            save(
                note,
                field.text.toString(),
                UUID.randomUUID().toString(),
                session
            )
        })
        else body.addView(ui.notice("이 계정은 조회만 할 수 있습니다. 저장 권한을 확인하려면 계정을 다시 연결해 주세요."))
        body.addView(ui.button("서버 메모 다시 조회") {
            if (field.text.toString() != note.text) AlertDialog.Builder(this)
                .setMessage("저장하지 않은 작성 내용은 사라집니다. 서버 메모를 다시 불러올까요?")
                .setPositiveButton("다시 조회") { _, _ -> load() }.setNegativeButton("계속 작성", null)
                .show()
            else load()
        })
        body.addView(ui.button("사건 화면으로 돌아가기") { finish() })
    }

    private fun save(
        note: PrivateReviewNote,
        text: String,
        requestId: String,
        expectedSession: String
    ) {
        if (lease?.valid(System.currentTimeMillis(), SystemClock.elapsedRealtime()) != true) {
            expired()
            return
        }
        val attempt = ++generation
        editor?.isEnabled = false
        for (index in 0 until body.childCount) if (body.getChildAt(index) is android.widget.Button) body.getChildAt(
            index
        ).isEnabled = false
        val status = ui.notice("메모 저장 결과를 확인하고 있습니다.")
        body.addView(status)
        worker.execute {
            val result = runCatching {
                AuthenticatedEventsClient(this).saveNoteForDisplay(
                    id,
                    note.revision,
                    text,
                    requestId,
                    expectedSession
                )
            }
            runOnUiThread {
                if (isDestroyed || attempt != generation) return@runOnUiThread
                result.fold(
                    onSuccess = { render(it, "메모를 저장했습니다. 외부 기관에 제출한 것은 아닙니다.") },
                    onFailure = { failure ->
                        val issue = failure as? AccountAccessException
                        status.text = if (issue?.reason in setOf(
                                AccountAccessException.Reason.CONFLICT,
                                AccountAccessException.Reason.FORBIDDEN,
                                AccountAccessException.Reason.SIGN_IN,
                                AccountAccessException.Reason.EXPIRED
                            )
                        ) issue?.message else
                            "저장 결과를 확인하지 못했습니다. 서버에는 반영됐을 수 있습니다."
                        if (issue?.reason !in setOf(
                                AccountAccessException.Reason.CONFLICT,
                                AccountAccessException.Reason.FORBIDDEN,
                                AccountAccessException.Reason.SIGN_IN,
                                AccountAccessException.Reason.EXPIRED,
                                AccountAccessException.Reason.CHANGED
                            )
                        )
                            body.addView(ui.button("같은 저장 요청 다시 확인", true) {
                                save(
                                    note,
                                    text,
                                    requestId,
                                    expectedSession
                                )
                            })
                        body.addView(ui.button("작성 내용 복사") {
                            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(
                                android.content.ClipData.newPlainText("내 확인 메모", text)
                            )
                        })
                        body.addView(ui.button("서버 메모 다시 조회") { load() })
                        body.addView(ui.button("돌아가기") { finish() })
                    })
            }
        }
    }
}
