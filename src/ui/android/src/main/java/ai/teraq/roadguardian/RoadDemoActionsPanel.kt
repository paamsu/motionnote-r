package ai.teraq.roadguardian

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ScrollView
import android.widget.LinearLayout
import org.json.JSONObject

/** 공모전 전용 사건별 접수 결과. 외부 신고나 보상 청구는 보내지 않는다 */
class RoadDemoActionsPanel(
    private val ui: PhoneAccountUi, private val event: JSONObject,
    private val allowed: () -> Boolean, private val stacked: Boolean = false
) : LinearLayout(ui.context) {
    private val legacy =
        context.getSharedPreferences("road_demo_receipts", android.content.Context.MODE_PRIVATE)
    private val legacyPrefix = "${event.optString("id")}:${event.optString("hash")}:"
    private var receiptDialog: Dialog? = null
    private var receiptContent: LinearLayout? = null
    private fun closeReceipts() {
        receiptContent?.removeAllViews()
        receiptContent = null
        receiptDialog?.dismiss()
        receiptDialog = null
    }

    private val watch = object : Runnable {
        override fun run() {
            if (!allowed()) {
                closeReceipts()
                removeAllViews()
            }
            if (isAttachedToWindow) postDelayed(this, 250)
        }
    }

    init {
        orientation = VERTICAL
        isBaselineAligned = false
        render()
    }

    private fun render() {
        removeAllViews()
        val actions = LinearLayout(context).apply {
            orientation = if (stacked) VERTICAL else HORIZONTAL
            isBaselineAligned = false
        }
        addView(actions, LayoutParams(-1, -2))
        var count = 0
        var hasLegacy = false
        for ((index, action) in listOf("report", "compensation").withIndex()) {
            val receipt = RoadDemoReceiptStore.read(context, event, action)
            val damaged = receipt == null && RoadDemoReceiptStore.hasRecord(context, event, action)
            val done = receipt != null
            val legacyOnly = !done && legacy.getBoolean(legacyPrefix + action, false)
            val label =
                if (damaged) "접수 기록 확인 필요" else if (legacyOnly) if (action == "report") "신고 근거 연결" else "보상 근거 연결" else if (action == "report") if (done) "안전신문고 전송 완료" else "안전신문고 전송" else if (done) "피해보상 접수 완료" else "피해보상 접수"
            actions.addView(
                ui.button(label, index == 0) {
                    if (isAttachedToWindow && allowed() && eligible(event) && !done && !damaged) {
                        runCatching {
                            RoadDemoReceiptStore.submit(
                                context,
                                event,
                                action
                            )
                        }.onSuccess { render() }
                            .onFailure {
                                android.widget.Toast.makeText(
                                    context,
                                    "접수 기록을 확인해 주세요",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                }.apply { isEnabled = !done && !damaged && eligible(event) },
                (if (stacked) LayoutParams(-1, -2) else LayoutParams(0, -2, 1f)).apply {
                    if (index == 0 && !stacked) marginEnd = ui.dp(16)
                    topMargin = ui.dp(16)
                    bottomMargin = ui.dp(16)
                })
            if (legacyOnly) {
                hasLegacy = true
                count++
            }
            if (receipt != null) {
                count++
            }
        }
        if (hasLegacy) addView(ui.text("이전 접수 이력 · 근거 연결 필요", 22f))
        if (count > 0) addView(ui.textButton("접수 근거 보기") { showReceipts() })
    }

    private fun showReceipts() {
        closeReceipts()
        if (!isAttachedToWindow || !allowed() || !eligible(event)) return
        // 접수 내용을 보여 주기 전에 이 사건 기준으로 다시 읽어 확인한다
        val records = listOf("report", "compensation").map {
            it to RoadDemoReceiptStore.read(
                context,
                event,
                it
            )
        }
        val dialog = Dialog(context, android.R.style.Theme_Material_Light_Dialog_NoActionBar)
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
            background = GradientDrawable().apply {
                setColor(ui.cardColor)
                cornerRadius = ui.dp(14).toFloat()
            }
        }
        content.addView(
            DriverEventGraphic(context, "record_saved", ui.light),
            LayoutParams(ui.dp(80), ui.dp(80)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        content.addView(ui.text("접수 근거", 24f, true))
        for ((action, record) in records) {
            val name = if (action == "report") "안전신문고" else "피해보상"
            content.addView(ui.text(name, 22f, true))
            val summary = when {
                record != null -> "접수번호 ${record.getString("receipt").removePrefix("MOCK-")}"
                RoadDemoReceiptStore.hasRecord(context, event, action) -> "접수 기록 확인 필요"
                legacy.getBoolean(legacyPrefix + action, false) -> "이전 접수 이력 · 근거 연결 필요"
                else -> "접수 전"
            }
            content.addView(ui.text(summary, 22f))
        }
        if (records.any { it.second != null }) {
            val photos = event.optJSONObject("surround_views")?.optJSONArray("views")?.length() ?: 0
            content.addView(
                ui.text(
                    if (photos > 0) "사진 ${photos}장 · 사건 위치 · 센서 기록" else "사건 위치 · 센서 기록",
                    22f
                )
            )
            content.addView(ui.text("이 사건의 보관 기록에 연결됨", 20f))
        }
        content.addView(ui.button("접수 근거 닫기") { closeReceipts() })
        val scroll = ScrollView(context).apply { addView(content) }
        dialog.setContentView(scroll)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        receiptDialog = dialog
        receiptContent = content
        dialog.setOnDismissListener {
            content.removeAllViews()
            if (receiptDialog === dialog) {
                receiptDialog = null
                receiptContent = null
            }
        }
        dialog.show()
        val metrics = resources.displayMetrics
        dialog.window?.setLayout(minOf(ui.dp(600), (metrics.widthPixels * .9f).toInt()), -2)
        scroll.post {
            if (scroll.height > metrics.heightPixels * .85f) dialog.window?.setLayout(
                minOf(ui.dp(600), (metrics.widthPixels * .9f).toInt()),
                (metrics.heightPixels * .85f).toInt()
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(watch)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(watch)
        closeReceipts()
        super.onDetachedFromWindow()
    }

    companion object {
        fun eligible(event: JSONObject) = RoadDemoReceiptStore.eligible(event)
    }
}
