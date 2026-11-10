package ai.teraq.roadguardian

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/** 정차 중에 보여 주는 가치 흐름 화면. 실제 QA나 서비스센터와 연결하지 않는다 */
class ContestValueActivity : Activity() {
    private lateinit var ui: PhoneAccountUi
    private lateinit var body: LinearLayout
    private lateinit var host: LinearLayout
    private var ux: VehicleUxMonitor? = null
    private var foreground = false
    private var record: JSONObject? = null
    private var details: Dialog? = null
    private var detailsContent: LinearLayout? = null
    private var page = 0
    private fun allowed() = foreground && ux?.restricted != true
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!BuildConfig.SYNTHETIC_BACKEND) {
            finish()
            return
        }
        ui = PhoneAccountUi(this, intent.getBooleanExtra("light", true))
        host = ui.install()
        body = host
        record = runCatching { ContestValueModel.load(this) }.getOrNull()
        page = record?.optInt("stage") ?: 0
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) {
            ux = VehicleUxMonitor(this) {
                runOnUiThread {
                    closeDetails()
                    render()
                }
            }
            ux?.start()
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (::body.isInitialized) render()
    }

    override fun onPause() {
        foreground = false
        closeDetails()
        if (::host.isInitialized) host.removeAllViews()
        super.onPause()
    }

    override fun onDestroy() {
        closeDetails()
        ux?.close()
        super.onDestroy()
    }

    private fun closeDetails() {
        detailsContent?.removeAllViews()
        detailsContent = null
        details?.dismiss()
        details = null
    }

    private fun advance(next: Int, reserve: Boolean = false) {
        if (!allowed() || record == null) return
        runCatching {
            ContestValueModel.save(
                this,
                maxOf(record!!.getInt("stage"), next),
                reserve || record!!.getBoolean("reserved")
            )
        }
            .onSuccess {
                record = it
                page = next
            }.onFailure { record = null }
        render()
    }

    private fun graphic(kind: String) {
        body.addView(
            DriverEventGraphic(this, kind, ui.light),
            LinearLayout.LayoutParams(-1, ui.dp(260))
        )
    }

    private fun render() {
        host.removeAllViews()
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        host.addView(ui.textButton("‹ 설정") { if (allowed()) finish() }
            .apply { layoutParams = LinearLayout.LayoutParams(-2, -2) })
        host.addView(stage, LinearLayout.LayoutParams(-1, -2, 1f))
        stage.addView(
            body,
            LinearLayout.LayoutParams(
                ui.dp(
                    (resources.configuration.screenWidthDp - 48).coerceIn(
                        1,
                        840
                    )
                ), -2
            )
        )
        if (!allowed()) {
            body.addView(ui.text("정차 후 확인해 주세요", 30f, true))
            return
        }
        val r = record
        if (r == null) {
            body.addView(ui.text("보관 근거 확인 필요", 30f, true))
            return
        }
        if (page == 0) {
            graphic("record_saved")
            body.addView(ui.text("고객의 기록에서 선제적 보호로", 32f, true))
            body.addView(ui.text("합성 차량 자료와 모의 센터 처리로 서비스 흐름을 시연합니다.", 22f))
            body.addView(ui.button("시연 시작", true) { advance(1) })
            return
        }
        body.addView(
            ui.text(
                when (page) {
                    1 -> "내 차량 기록"
                    2 -> "서비스센터 · 기록 수신"
                    3 -> "그룹 · 품질 검토"
                    else -> "내 차량 · 점검 안내"
                }, 22f
            ).apply {
                gravity = Gravity.CENTER
                setTextColor(ui.muted)
            })
        val title = when (page) {
            1 -> "지금은 정상이어도, 기록은 남습니다"
            2 -> "센터에 당시 기록 전달 완료"
            3 -> "같은 차종의 반복 징후 확인"
            else -> if (r.getBoolean("reserved")) "서비스센터 예약 완료" else "전원 계통 점검을 권장합니다"
        }
        body.addView(ui.text(title, 36f, true).apply { gravity = Gravity.CENTER })
        when (page) {
            1 -> {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for ((label, kind) in listOf(
                    "이전 · 저전압" to "voltage_low",
                    "현재 · 전압 정상" to "voltage_normal"
                )) {
                    val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    card.addView(
                        DriverEventGraphic(this, kind, ui.light),
                        LinearLayout.LayoutParams(-1, ui.dp(230))
                    )
                    card.addView(ui.text(label, 26f, true).apply { gravity = Gravity.CENTER })
                    row.addView(card, LinearLayout.LayoutParams(0, -2, 1f))
                }
                body.addView(row)
                body.addView(
                    ui.text("진단기 고장코드 없음 · 이전 기록 보관", 24f).apply { gravity = Gravity.CENTER })
                body.addView(ui.button("센터에 기록 전달", true) { advance(2) })
            }

            2 -> {
                graphic("record_saved")
                body.addView(
                    ui.text("이전 이상과 현재 정상 · 함께 전달했습니다", 26f).apply { gravity = Gravity.CENTER })
                body.addView(ui.button("차종별 반복 징후 보기", true) { advance(3) })
            }

            3 -> {
                val g = r.getJSONObject("group")
                val cars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val rows = r.getJSONArray("observations")
                val members = g.getJSONArray("evidence")
                val counts = linkedMapOf<String, Int>()
                for (i in 0 until members.length()) for (j in 0 until rows.length()) {
                    val raw = rows.getJSONObject(j).getJSONObject("raw")
                    if (raw.getString("id") == members.getJSONObject(i)
                            .getString("id")
                    ) counts[raw.getString("vehicle")] = (counts[raw.getString("vehicle")] ?: 0) + 1
                }
                counts.values.forEachIndexed { index, count ->
                    val vehicle = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                    }
                    vehicle.addView(
                        CockpitIcon(this, "차량", ui.ink),
                        LinearLayout.LayoutParams(ui.dp(170), ui.dp(170))
                    )
                    vehicle.addView(
                        ui.text("차량 ${index + 1} · 저전압 ${count}회", 24f, true)
                            .apply { gravity = Gravity.CENTER })
                    cars.addView(vehicle, LinearLayout.LayoutParams(0, -2, 1f))
                }
                body.addView(cars, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = ui.dp(28)
                    bottomMargin = ui.dp(28)
                })
                body.addView(
                    ui.text(
                        "${g.getString("model")} · ${g.getInt("vehicle_count")}대 / ${
                            g.getInt(
                                "observation_count"
                            )
                        }회", 28f, true
                    ).apply { gravity = Gravity.CENTER })
                body.addView(
                    ui.text("같은 조건의 반복 징후 · 점검 검토 필요", 24f).apply { gravity = Gravity.CENTER })
                body.addView(ui.button("대상 고객에게 점검 안내", true) { advance(4) })
            }

            else -> {
                body.addView(
                    CockpitIcon(
                        this,
                        if (r.getBoolean("reserved")) "check" else "기록",
                        ui.ink
                    ), LinearLayout.LayoutParams(ui.dp(180), ui.dp(180)).apply {
                        topMargin = ui.dp(28)
                        bottomMargin = ui.dp(28)
                    })
                if (r.getBoolean("reserved")) {
                    val booking = r.getJSONObject("reservation")
                    body.addView(
                        ui.text(
                            "${booking.getString("center_name")} · ${
                                booking.getString(
                                    "appointment"
                                )
                            }", 28f, true
                        ).apply { gravity = Gravity.CENTER })
                }
                body.addView(
                    ui.text(
                        if (r.getBoolean("reserved")) "당시 기록과 점검 안내가 센터에 연결됐습니다" else "반복 징후를 바탕으로 먼저 안내드립니다",
                        26f
                    ).apply { gravity = Gravity.CENTER })
                if (!r.getBoolean("reserved")) body.addView(ui.button("서비스센터 예약", true) {
                    advance(
                        4,
                        true
                    )
                })
            }
        }
        body.addView(ui.textButton("연결 근거 보기") { showDetails() })
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (page > 1) nav.addView(ui.textButton("이전 장면") {
            if (allowed()) {
                page--
                render()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (page < r.getInt("stage")) nav.addView(ui.textButton("다음 장면") {
            if (allowed()) {
                page++
                render()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(nav)
    }

    private fun showDetails() {
        if (!allowed()) return
        val checked = runCatching { ContestValueModel.load(this) }.getOrNull() ?: run {
            record = null
            render()
            return
        }
        closeDetails()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
            background = GradientDrawable().apply {
                setColor(ui.cardColor)
                cornerRadius = ui.dp(14).toFloat()
            }
        }
        content.addView(ui.text("같은 기록으로 이어진 보호", 26f, true))
        val original = checked.getJSONArray("observations").getJSONObject(0)
        content.addView(ui.text("이전 11.8 V → 현재 14.2 V\n현재 진단기 관측: 고장코드 없음", 22f))
        content.addView(ui.text("동일 차종·조건 2대 / 3회\n정상 1건 · 다른 차종 1건 제외", 22f))
        checked.optJSONObject("center")
            ?.let { content.addView(ui.text("${it.getString("name")} · 당시 원본 확보", 22f)) }
        checked.optJSONObject("notice")
            ?.let { content.addView(ui.text("고객 점검 안내 · 같은 차종의 반복 징후 연결", 22f)) }
        checked.optJSONObject("reservation")?.let {
            content.addView(
                ui.text(
                    "${it.getString("center_name")} · ${it.getString("appointment")} 방문",
                    22f
                )
            )
        }
        val raw = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raw.addView(
            ui.text(
                "최초 원본 SHA-256\n${original.getString("sha256")}\n${checked.toString(2)}",
                16f
            )
        )
        ui.disclosure(content, "원본·연결 식별 정보", raw)
        content.addView(ui.button("닫기") { closeDetails() })
        val dialog = Dialog(this, android.R.style.Theme_Material_Light_Dialog_NoActionBar)
        dialog.setContentView(ScrollView(this).apply { addView(content) })
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        details = dialog
        detailsContent = content
        dialog.setOnDismissListener {
            content.removeAllViews()
            if (details === dialog) {
                details = null
                detailsContent = null
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            minOf(
                ui.dp(760),
                (resources.displayMetrics.widthPixels * .9).toInt()
            ), (resources.displayMetrics.heightPixels * .8).toInt()
        )
    }
}
