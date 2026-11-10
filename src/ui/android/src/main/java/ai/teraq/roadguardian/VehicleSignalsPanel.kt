package ai.teraq.roadguardian

import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** 정차 중 차량 상태 화면. 주행 제한은 호출한 쪽이 맡는다 */
class VehicleSignalsPanel(
    context: Context,
    private val light: Boolean,
    private val snapshot: () -> JSONObject
) : LinearLayout(context) {
    private val graphic = OverviewGraphic(context, light)
    private val labels = mutableListOf<TextView>()
    private val textMode =
        resources.configuration.screenWidthDp < 760 || resources.configuration.fontScale >= 1.3f
    private val refresh = object : Runnable {
        override fun run() {
            update()
            postDelayed(this, 1000)
        }
    }

    init {
        orientation = VERTICAL
        if (textMode) repeat(5) {
            val label = TextView(context).apply {
                textSize = 24f
                setTextColor(PleosPalette.ink(light))
                setPadding(0, dp(10), 0, dp(10))
            }
            labels.add(label)
            addView(label, LayoutParams(-1, -2))
        } else addView(graphic, LayoutParams(-1, -2))
        update()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun update() {
        val value = VehicleSignalSummary.from(snapshot())
        if (textMode) {
            val lines = listOf(
                value.gearLabel,
                "속도 ${if (value.speed == "—") "미확인" else "${value.speed} km/h"}",
                "앞 와이퍼 · ${value.wiper}",
                "에어백 · ${value.airbag}",
                value.pressureLabel
            )
            labels.forEachIndexed { i, label -> label.text = lines[i] }
        } else {
            graphic.value = value
            graphic.contentDescription = value.description
            graphic.invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    private class OverviewGraphic(context: Context, private val light: Boolean) : View(context) {
        var value = VehicleSignalSummary.from(JSONObject())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val density = resources.displayMetrics.density
            val h = max(260 * density, min(520 * density, w * .39f)).toInt()
            setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = min(width / 1000f, height / 390f)
            canvas.save()
            canvas.translate((width - 1000 * scale) / 2, (height - 390 * scale) / 2)
            canvas.scale(scale, scale)
            val ink = PleosPalette.ink(light)
            val muted = PleosPalette.muted(light)
            val accent = PleosPalette.action(light)
            val line = Color.parseColor(if (light) "#D6DFE5" else "#343B43")
            val amber = Color.parseColor(if (light) "#805000" else "#FFC777")
            fun text(
                value: String,
                x: Float,
                y: Float,
                size: Float,
                color: Int = ink,
                bold: Boolean = false
            ) {
                paint.style = Paint.Style.FILL
                paint.color = color
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = size
                paint.typeface = PleosTypography.face(bold)
                canvas.drawText(value, x, y, paint)
            }
            text("기어", 35f, 48f, 20f, muted)
            text(value.gear, 35f, 155f, 105f, accent, true)
            text(value.gearLabel, 35f, 190f, 22f, muted)
            text(value.speed, 35f, 292f, 68f, ink, true)
            text("km/h", 170f, 292f, 22f, muted)
            paint.color = line
            paint.strokeWidth = 1f
            canvas.drawLine(290f, 35f, 290f, 315f, paint)
            paint.color = muted
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f
            canvas.drawRoundRect(RectF(435f, 42f, 565f, 300f), 38f, 38f, paint)
            canvas.drawRoundRect(RectF(451f, 103f, 549f, 152f), 10f, 10f, paint)
            canvas.drawRoundRect(RectF(451f, 238f, 549f, 269f), 8f, 8f, paint)
            paint.color = line
            canvas.drawLine(445f, 186f, 555f, 186f, paint)
            for (i in 0..3) {
                val x = if (i % 2 == 0) 435f else 565f
                val y = if (i < 2) 112f else 254f
                paint.style = Paint.Style.FILL
                paint.color = if (value.tires[i]) accent else muted
                canvas.drawRoundRect(RectF(x - 6f, y - 22f, x + 6f, y + 22f), 4f, 4f, paint)
            }
            text("전면", 479f, 25f, 15f, muted)
            text(value.pressureLabel, 355f, 335f, 17f, muted)
            text("앞 와이퍼", 710f, 60f, 20f, muted)
            text(value.wiper, 710f, 101f, 27f, if (value.wiper == "미확인") muted else accent, true)
            paint.color = line
            paint.strokeWidth = 1f
            canvas.drawLine(710f, 131f, 975f, 131f, paint)
            text("에어백", 710f, 177f, 20f, muted)
            text(value.airbag, 710f, 218f, 27f, if (value.deployed) amber else ink, true)
            text("조회 응답 기준", 710f, 250f, 17f, muted)
            text("차량 신호 수신 현황 · 고장·사고 여부는 별도 확인이 필요합니다", 35f, 380f, 18f, muted)
            canvas.restore()
        }
    }
}
