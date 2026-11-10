package ai.teraq.roadguardian

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.concurrent.Executors

/** 로컬 시연 선택 화면. 보험사 호출, 센터 예약, 서버 상태 변경은 하지 않는다 */
class DemoActionPanel(
    private val ui: PhoneAccountUi,
    private val event: JSONObject,
    private val client: BackendClient,
    private val allowed: () -> Boolean,
    private val onEventChanged: ((JSONObject) -> Unit)? = null,
    private val onPrepared: (() -> Unit)? = null,
    private val compactPresentation: Boolean = false
) : LinearLayout(ui.context) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var worker: java.util.concurrent.ExecutorService? = null
    private var generation = 0L
    private var busy = false
    private var stateChanged = false

    private class EventChanged(val current: JSONObject) : Exception("Event state changed")

    private val action = DemoActionDraftStore.eligibleAction(event)
    private val watch = object : Runnable {
        override fun run() {
            if (!allowed()) {
                generation++
                removeAllViews()
            }
            if (isAttachedToWindow) main.postDelayed(this, 200)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.START
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        worker = Executors.newSingleThreadExecutor()
        main.post(watch)
        if (stateChanged) {
            removeAllViews()
            label("상태가 바뀌었습니다")
        } else load(null)
    }

    override fun onDetachedFromWindow() {
        generation++
        main.removeCallbacks(watch)
        worker?.shutdownNow()
        worker = null
        super.onDetachedFromWindow()
    }

    private val actionScale: Float
        get() = if (compactPresentation) 1.15f else if (action in setOf(
                "insurance",
                "service",
                "service_handoff"
            ) && resources.configuration.screenWidthDp >= 760 && resources.configuration.screenHeightDp >= 600
        ) 1.65f else 1f

    private fun label(value: String) {
        addView(ui.text(value, 23f * actionScale, true).apply {
            gravity = if (compactPresentation) Gravity.START else Gravity.CENTER
        })
    }

    private fun secondary(value: String, action: () -> Unit) = ui.textButton(value, action).apply {
        if (compactPresentation) {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, paddingTop, 0, paddingBottom)
            layoutParams = LayoutParams(-1, -2).apply { topMargin = ui.dp(8) }
        }
    }

    private fun fresh(): JSONObject {
        val current = JSONObject(client.request("/api/events/${event.getString("id")}"))
        require(
            current.getString("id") == event.getString("id") && current.getString("hash") == event.getString(
                "hash"
            ) &&
                    current.opt("source") == "synthetic" && current.opt("source") == event.opt("source")
        )
        val expected = checkNotNull(DemoActionDraftStore.fingerprint(event))
        if (DemoActionDraftStore.fingerprint(current) != expected) throw EventChanged(
            EvidenceJson.copy(
                current
            )
        )
        return current
    }

    /** 실제 버튼이 부르는 함수. 시험 코드는 이 함수를 직접 부른다 */
    fun choose(prepare: Boolean) {
        if (!isAttachedToWindow || !allowed() || busy || action == null || stateChanged) return
        load(prepare)
    }

    private fun load(choice: Boolean?) {
        if (action == null || !allowed() || stateChanged) return
        busy = true
        val ticket = ++generation
        removeAllViews()
        label(if (choice == null) "준비 상태 확인 중" else "선택 저장 중")
        worker?.execute {
            val result = runCatching {
                fresh()
                if (choice != null) {
                    // 저장 직전에 다시 확인한다. 이 읽기로 서버를 잠그지는 않는다
                    val beforeWrite = fresh()
                    if (choice) DemoActionDraftStore.prepare(
                        context,
                        beforeWrite
                    ) else DemoActionDraftStore.defer(context, beforeWrite)
                }
                val checked = fresh()
                val draft = DemoActionDraftStore.read(context, checked)
                check(
                    draft != null || !DemoActionDraftStore.hasRecord(
                        context,
                        checked
                    )
                ) { "Saved draft needs verification" }
                draft
            }
            main.post {
                if (ticket != generation || !isAttachedToWindow || !allowed()) return@post
                busy = false
                removeAllViews()
                result.fold(onSuccess = { render(it) }, onFailure = { failure ->
                    if (failure is EventChanged) {
                        // 새 화면을 요청하기 전에 기존 버튼을 멈춘다. 임시 저장 내용은 여기서 다시 쓰지 않는다
                        stateChanged = true
                        generation++
                        label("상태가 바뀌었습니다")
                        onEventChanged?.invoke(failure.current)
                    } else {
                        label("준비 상태 확인 필요")
                        addView(ui.button("다시 확인") { load(null) })
                    }
                })
            }
        }
    }

    private fun render(draft: JSONObject?) {
        val prepared = draft?.optString("state") == "prepared"
        val serviceTarget = ServiceDemoRecommendation.checked(event)
        if (serviceTarget != null) {
            label(ServiceDemoRecommendation.summary(serviceTarget))
            val provenance = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(
                    ui.text(
                        "작성된 보증 상태와 센터 목록으로 선택했습니다. 거리·가용성은 시연 설정이며 실제 예약이나 보증 조회는 하지 않습니다.",
                        18f
                    )
                )
            }
            val disclosureIndex = childCount
            ui.disclosure(this, "추천 근거", provenance)
            if (compactPresentation) (getChildAt(disclosureIndex) as? android.widget.Button)?.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, paddingTop, 0, paddingBottom)
                layoutParams = LayoutParams(-1, -2).apply { bottomMargin = ui.dp(16) }
            }
        }
        if (prepared) {
            if (onPrepared != null) onPrepared.invoke() else addView(
                DriverEventGraphic(
                    context,
                    "record_saved",
                    ui.light
                ), LayoutParams(-1, ui.dp(100))
            )
            if (compactPresentation) addView(
                DriverEventGraphic(context, "record_saved", ui.light),
                LayoutParams(ui.dp(100), ui.dp(100)).apply {
                    gravity = Gravity.START
                    bottomMargin = ui.dp(16)
                })
            // 연결된 서비스 화면은 제목에 완료 상태를 이미 보여 준다
            if (serviceTarget == null || onPrepared == null)
                label(if (action == "service_handoff") "이전 이상·현재 회복 기록 전달 완료" else if (serviceTarget != null) "예약 완료" else if (compactPresentation) "기록 전달 완료" else if (onPrepared != null) "기록 보관 완료" else if (action == "insurance") "보험 접수 완료" else "예약 완료")
            if (compactPresentation) addView(secondary("이전 화면으로") {
                if (allowed()) (context as? android.app.Activity)?.finish()
            })
            return
        }
        if (draft?.optString("state") == "deferred") label("나중에 확인")
        else if (serviceTarget != null) label("이 센터를 예약할까요?")
        else label(
            when (action) {
                "insurance" -> "보험사를 연결할까요?"
                "service_handoff" -> "이전 이상 기록을 센터에 전달할까요?"
                else -> "근처 서비스센터를 예약할까요?"
            }
        )
        // 버튼은 글자 기준선 대신 전체 높이로 맞춘다. 기준선으로 맞추면 버튼 아래가 잘릴 수 있다
        val buttons = LinearLayout(context).apply {
            orientation = if (compactPresentation) VERTICAL else HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.TOP
        }
        val choiceTextSize = if (actionScale > 1f) 34f else 24f
        buttons.addView(
            ui.button(
                when (action) {
                    "insurance" -> "보험사 연결"
                    "service_handoff" -> "서비스센터 기록 전달"
                    else -> if (serviceTarget != null) "예약하기" else "서비스센터 예약"
                }, primary = true, automotiveTextSizeSp = choiceTextSize
            ) { choose(true) },
            (if (compactPresentation) LayoutParams(-1, -2) else LayoutParams(
                0,
                -2,
                1f
            )).apply { if (!compactPresentation) marginEnd = ui.dp(12) })
        if (draft == null) {
            if (compactPresentation) buttons.addView(secondary("나중에") { choose(false) })
            else buttons.addView(ui.button("나중에", automotiveTextSizeSp = choiceTextSize) {
                choose(
                    false
                )
            }, LayoutParams(0, -2, 1f))
        }
        addView(
            buttons,
            LayoutParams(-1, -2).apply { if (compactPresentation) topMargin = ui.dp(12) })
    }
}
