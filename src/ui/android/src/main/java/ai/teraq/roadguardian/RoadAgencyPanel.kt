package ai.teraq.roadguardian

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/** 정차 중 보는 기관 접수 결과. 포그라운드와 차량 제한은 화면을 띄운 쪽이 맡는다 */
class RoadAgencyPanel(
    private val ui: PhoneAccountUi,
    private val bundle: JSONObject,
    private val allowed: () -> Boolean
) : LinearLayout(ui.context) {
    private var dialog: Dialog? = null
    private var detail: LinearLayout? = null
    private fun close() {
        detail?.removeAllViews()
        detail = null
        dialog?.dismiss()
        dialog = null
    }

    private val watch = object : Runnable {
        override fun run() {
            if (!allowed()) {
                close()
                removeAllViews()
            }
            if (isAttachedToWindow) postDelayed(this, 250)
        }
    }

    init {
        orientation = VERTICAL
        render()
    }

    private fun render() {
        removeAllViews()
        val receipt = RoadAgencyReceiptStore.read(context, bundle)
        val blocked = receipt == null && RoadAgencyReceiptStore.exists(context, bundle)
        addView(
            ui.text(
                "관측 ${bundle.getInt("observation_count")}건 · 차량 ${bundle.getInt("vehicle_count")}대",
                ui.evidenceCaptionSize,
                true
            )
        )
        if (receipt != null) addView(
            DriverEventGraphic(context, "record_saved", ui.light),
            LayoutParams(-1, ui.dp(64))
        )
        addView(
            ui.button(
                if (receipt != null) "기관 제공 완료" else if (blocked) "이전 제공 근거 확인 필요" else "도로 관리기관에 제공",
                true
            ) {
                if (!isAttachedToWindow || !allowed()) return@button
                runCatching {
                    RoadAgencyReceiptStore.submit(
                        context,
                        bundle
                    )
                }.onSuccess { render() }.onFailure {
                    android.widget.Toast.makeText(
                        context,
                        "제공 근거를 확인해 주세요",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }.apply { isEnabled = receipt == null && !blocked })
        if (receipt != null) addView(ui.textButton("기관 접수 근거 보기") { show() })
        if (RoadAgencyReceiptStore.archive(context, bundle.getString("group_id"))
                .isNotEmpty()
        ) addView(ui.textButton("이전 제공 기록") { showArchive() })
        if (blocked) addView(ui.text("기존 접수 기록은 보존됐습니다", 22f))
    }

    private fun show(selected: String? = null) {
        if (!isAttachedToWindow || !allowed()) return
        val receipt = (if (selected == null) RoadAgencyReceiptStore.read(
            context,
            bundle
        ) else RoadAgencyReceiptStore.archive(context, bundle.getString("group_id"))
            .mapNotNull { it.optJSONObject("record") }
            .firstOrNull { it.optString("receipt") == selected }) ?: run {
            render()
            return
        }
        val shown = receipt.getJSONObject("bundle")
        close()
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
            background = GradientDrawable().apply {
                setColor(ui.cardColor)
                cornerRadius = ui.dp(14).toFloat()
            }
        }
        box.addView(ui.text("도로 관리기관 접수", 28f, true))
        box.addView(
            ui.text(
                "접수번호 ${receipt.getString("receipt").removePrefix("MOCK-")}",
                24f,
                true
            )
        )
        box.addView(
            ui.text(
                "관측 ${shown.getInt("observation_count")}건 · 차량 ${shown.getInt("vehicle_count")}대\n${
                    if (shown.optInt(
                            "photo_count",
                            0
                        ) > 0
                    ) "사진 ${shown.getInt("photo_count")}장·위치·원본 기록 연결" else "위치·원본 기록 연결"
                }", 22f
            )
        )
        val original = LinearLayout(context).apply { orientation = VERTICAL }
        original.addView(ui.text(receipt.toString(2), 16f))
        ui.disclosure(box, "제공 묶음과 식별 정보", original)
        box.addView(ui.button("접수 근거 닫기") { close() })
        openDialog(box)
    }

    private fun openDialog(box: LinearLayout) {
        val d = Dialog(context, android.R.style.Theme_Material_Light_Dialog_NoActionBar)
        d.setContentView(ScrollView(context).apply { addView(box) })
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog = d
        detail = box
        d.setOnDismissListener {
            box.removeAllViews()
            if (dialog === d) {
                dialog = null
                detail = null
            }
        }
        d.show()
        d.window?.setLayout(
            minOf(ui.dp(680), (resources.displayMetrics.widthPixels * .9).toInt()),
            (resources.displayMetrics.heightPixels * .7).toInt()
        )
    }

    private fun showArchive() {
        if (!isAttachedToWindow || !allowed()) return
        close()
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
            background = GradientDrawable().apply {
                setColor(ui.cardColor)
                cornerRadius = ui.dp(14).toFloat()
            }
        }
        box.addView(ui.text("이전 제공 기록", 28f, true))
        for (entry in RoadAgencyReceiptStore.archive(context, bundle.getString("group_id"))) {
            val saved = entry.optJSONObject("record")
            if (saved == null) box.addView(ui.text("보존된 접수 기록 · 근거 확인 필요", 22f))
            else {
                val id = saved.getString("receipt")
                box.addView(
                    ui.button(
                        "${id.removePrefix("MOCK-")} · 관측 ${
                            saved.getJSONObject("bundle").getInt("observation_count")
                        }건"
                    ) { show(id) })
            }
        }
        box.addView(ui.button("닫기") { close() })
        openDialog(box)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(watch)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(watch)
        close()
        super.onDetachedFromWindow()
    }
}
