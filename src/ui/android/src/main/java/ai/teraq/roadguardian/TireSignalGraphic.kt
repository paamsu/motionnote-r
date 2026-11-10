package ai.teraq.roadguardian

import android.content.Context
import android.graphics.*
import android.view.View
import org.json.JSONObject

/** 바퀴별 수신 여부를 보여 준다. 공기압 값을 받았다고 안전 표시(초록)를 하지 않는다 */
class TireSignalGraphic(
    context: Context,
    private val light: Boolean,
    private val snapshot: () -> JSONObject
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = snapshot()
        val wheels = data.optJSONObject("wheels")
        val ink = PleosPalette.ink(light)
        val muted = PleosPalette.muted(light)
        val accent = PleosPalette.action(light)
        val cx = width / 2f
        val half = dp(37f)
        val top = dp(30f)
        val bottom = height - dp(24f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = muted
        canvas.drawRoundRect(RectF(cx - half, top, cx + half, bottom), dp(23f), dp(23f), paint)
        canvas.drawRoundRect(
            RectF(
                cx - half + dp(10f),
                top + dp(30f),
                cx + half - dp(10f),
                top + dp(57f)
            ), dp(5f), dp(5f), paint
        )
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 12f * resources.displayMetrics.scaledDensity
        canvas.drawText("전면", cx, dp(17f), paint)
        val descriptions = mutableListOf<String>()
        for ((i, wheel) in PleosTireMonitor.WHEELS.withIndex()) {
            val fresh =
                wheels?.optJSONObject(wheel)?.optJSONObject("pressure")?.optBoolean("fresh") == true
            val left = i % 2 == 0
            val front = i < 2
            val y = if (front) top + dp(47f) else bottom - dp(35f)
            val x = if (left) cx - half else cx + half
            paint.color = if (fresh) accent else muted
            canvas.drawRoundRect(
                RectF(x - dp(5f), y - dp(15f), x + dp(5f), y + dp(15f)),
                dp(3f),
                dp(3f),
                paint
            )
            val label = when (wheel) {
                "LEFT_FRONT" -> "앞 왼쪽"
                "RIGHT_FRONT" -> "앞 오른쪽"
                "LEFT_REAR" -> "뒤 왼쪽"
                else -> "뒤 오른쪽"
            }
            val status = if (fresh) "값 수신" else "미확인"
            val labelX = if (left) width * .2f else width * .8f
            paint.textSize = 14f * resources.displayMetrics.scaledDensity
            paint.color = muted
            canvas.drawText(label, labelX, y - dp(5f), paint)
            paint.color = if (fresh) accent else ink
            paint.textSize = 19f * resources.displayMetrics.scaledDensity
            canvas.drawText(status, labelX, y + dp(22f), paint)
            descriptions.add("$label $status")
        }
        contentDescription = "타이어 신호 연결. ${descriptions.joinToString(". ")}. 공기압 안전 판정은 하지 않습니다."
    }
}
