package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONObject
import kotlin.math.min

/** 작성하거나 조정한 합성 영상 좌표를 그린다 */
class TrafficMotionGraphic(context: Context, motion: JSONObject, private val light: Boolean) :
    View(context) {
    private val points = motion.getJSONArray("points")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = PleosTypography.face() }

    init {
        contentDescription = "가상 상대 횡방향 위치. 가로축 시간 0~${
            points.getJSONObject(points.length() - 1).getDouble("t")
        }초, 세로축 미터. 청록 실선은 주변 차량, 회색 점선은 본인 차량. " + if (motion.optString("source") == "synthetic_video_tracking") "합성 영상 색 표식 시험이며 실제 차량 인식 결과가 아닙니다." else "영상 분석 결과가 아닙니다."
        isFocusable = true
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val narrow = width / resources.displayMetrics.density < 600
        val scale = min(width / 1000f, height / (if (narrow) 300f else 230f))
        c.save()
        c.translate((width - 1000 * scale) / 2, 0f)
        c.scale(scale, scale)
        val font =
            if (narrow) maxOf(19f, 14f * resources.displayMetrics.scaledDensity / scale) else 19f
        val left = if (narrow) 130f else 80f
        val ink = Color.parseColor(if (light) "#526879" else "#BDBDBD")
        val accent = PleosPalette.action(light)
        fun label(s: String, x: Float, y: Float) {
            paint.color = ink
            paint.style = Paint.Style.FILL
            paint.textSize = font
            c.drawText(s, x, y, paint)
        }

        val legendY = if (narrow) 42f else 22f
        val egoX = if (narrow) 570f else 300f
        label("주변 차량", 90f, legendY)
        paint.color = accent
        paint.strokeWidth = 4f
        c.drawLine(40f, legendY - 6, 78f, legendY - 6, paint)
        label("본인 차량", egoX, legendY)
        paint.color = ink
        paint.strokeWidth = 2f
        c.drawLine(egoX - 50, legendY - 6, egoX - 13, legendY - 6, paint)
        val first = points.getJSONObject(0).getDouble("t")
        val last = points.getJSONObject(points.length() - 1).getDouble("t")
        fun x(t: Double) = left + ((t - first) / (last - first) * (960 - left)).toFloat()
        val extent = kotlin.math.ceil((0 until points.length()).flatMap {
            listOf(
                kotlin.math.abs(
                    points.getJSONObject(it).getDouble("ego_lateral_m")
                ), kotlin.math.abs(points.getJSONObject(it).getDouble("target_lateral_m"))
            )
        }.maxOrNull()?.coerceAtLeast(4.0) ?: 4.0)

        fun y(v: Double) =
            (if (narrow) 165f else 125f) - (v / extent * (if (narrow) 55 else 64)).toFloat()
        for (v in listOf(-extent, 0.0, extent)) {
            paint.color = Color.parseColor(if (light) "#DEDEDE" else "#3B434B")
            paint.strokeWidth = 1f
            c.drawLine(left, y(v.toDouble()), 960f, y(v.toDouble()), paint)
            label("${v.toInt()} m", 5f, y(v.toDouble()) + font * .3f)
        }
        for (key in listOf("ego_lateral_m", "target_lateral_m")) {
            val path = Path()
            for (i in 0 until points.length()) {
                val p = points.getJSONObject(i)
                if (i == 0) path.moveTo(
                    x(p.getDouble("t")),
                    y(p.getDouble(key))
                ) else path.lineTo(x(p.getDouble("t")), y(p.getDouble(key)))
            }
            paint.color = if (key == "target_lateral_m") accent else ink
            paint.strokeWidth = if (key == "target_lateral_m") 4f else 2f
            paint.style = Paint.Style.STROKE
            paint.pathEffect = if (key == "ego_lateral_m") android.graphics.DashPathEffect(
                floatArrayOf(8f, 7f),
                0f
            ) else null
            c.drawPath(path, paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
        }
        fun seconds(value: Double) =
            if (value == value.toInt().toDouble()) "${value.toInt()}초" else "${value}초"

        val bottom = if (narrow) 285f else 220f
        label(seconds(first), left, bottom)
        paint.textAlign = Paint.Align.RIGHT
        label(seconds(last), 960f, bottom)
        paint.textAlign = Paint.Align.LEFT
        c.restore()
    }
}
