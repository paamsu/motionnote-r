package ai.teraq.roadguardian

import android.content.Context
import android.graphics.*
import android.view.View
import org.json.JSONArray
import kotlin.math.*

/** 직접 그린 벡터 그림. 지도는 시험용 가상 지역이다 */
class SafetyMapView(context: Context, private val day: Boolean, private val hazard: Boolean) :
    View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private fun color(s: String) = Color.parseColor(s)
    private fun fill(canvas: Canvas, points: FloatArray, tint: Int) {
        path.reset()
        path.moveTo(points[0], points[1])
        var i = 2
        while (i < points.size) {
            path.lineTo(points[i], points[i + 1])
            i += 2
        }
        path.close()
        p.style = Paint.Style.FILL
        p.color = tint
        canvas.drawPath(path, p)
    }

    private fun road(canvas: Canvas, points: FloatArray, width: Float, edge: Int, center: Int) {
        path.reset()
        path.moveTo(points[0], points[1])
        var i = 2
        while (i < points.size) {
            path.lineTo(points[i], points[i + 1])
            i += 2
        }
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeCap = Paint.Cap.ROUND
        p.color = edge
        p.strokeWidth = width + 3
        canvas.drawPath(path, p)
        p.color = center
        p.strokeWidth = width
        canvas.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    private fun label(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float = 15f,
        tint: Int = if (day) color("#68787A") else color("#9CAEBB")
    ) {
        p.typeface = PleosTypography.face()
        p.textSize = size
        p.color = tint
        p.style = Paint.Style.FILL
        canvas.drawText(value, x, y, p)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / 1200f, height / 660f)
        canvas.drawColor(if (day) color("#E9EDEB") else color("#202A31"))
        val block = if (day) color("#DEE3E0") else color("#28353D")
        val edge = if (day) color("#CBD3D1") else color("#46535B")
        val street = if (day) color("#FAFBF8") else color("#35444E")
        // 주변 블록은 경로와 경고보다 흐리게 그린다
        p.color = block
        for (r in 0..6) for (c in 0..11) {
            val x = c * 110f - 35 + (r % 2) * 12
            val y = r * 100f - 20
            rect.set(x + 14, y + 16, x + 88, y + 73)
            canvas.drawRoundRect(rect, 7f, 7f, p)
        }
        fill(
            canvas,
            floatArrayOf(0f, 390f, 130f, 350f, 255f, 360f, 330f, 450f, 260f, 570f, 0f, 620f),
            if (day) color("#D0DECC") else color("#283F36")
        )
        fill(
            canvas,
            floatArrayOf(840f, 0f, 1000f, 0f, 920f, 170f, 755f, 230f, 680f, 160f),
            if (day) color("#D0DECC") else color("#283F36")
        )
        path.reset()
        path.moveTo(190f, -50f)
        path.cubicTo(390f, 80f, 230f, 180f, 390f, 280f)
        path.cubicTo(520f, 360f, 390f, 540f, 570f, 730f)
        p.style = Paint.Style.STROKE
        p.color = if (day) color("#B6D5DE") else color("#203E50")
        p.strokeWidth = 72f
        canvas.drawPath(path, p)
        p.strokeWidth = 2f
        p.color = if (day) color("#9ABDC7") else color("#315062")
        canvas.drawPath(path, p)
        p.style = Paint.Style.FILL
        for (r in 0..6) road(
            canvas,
            floatArrayOf(-20f, r * 100f + 75, 1200f, r * 100f + 75),
            8f,
            edge,
            street
        )
        for (c in 0..11) road(
            canvas,
            floatArrayOf(c * 110f + 65, -20f, c * 110f + 65, 700f),
            7f,
            edge,
            street
        )
        road(
            canvas,
            floatArrayOf(-40f, 590f, 180f, 490f, 390f, 420f, 650f, 330f, 880f, 235f, 1220f, 150f),
            28f,
            edge,
            street
        )
        road(
            canvas,
            floatArrayOf(515f, 720f, 530f, 490f, 560f, 330f, 690f, 190f, 700f, -20f),
            30f,
            edge,
            street
        )
        road(
            canvas,
            floatArrayOf(0f, 185f, 310f, 195f, 560f, 330f, 820f, 480f, 1220f, 525f),
            21f,
            edge,
            street
        )
        // 가상 경로. 흰 테두리로 교차 도로 위에서도 잘 보이게 한다
        road(
            canvas,
            floatArrayOf(525f, 650f, 532f, 490f, 560f, 330f, 690f, 190f, 700f, -20f),
            12f,
            if (day) Color.WHITE else color("#16232C"),
            if (day) color("#3C78DA") else color("#88BCFF")
        )
        label(canvas, "중앙대로", 585f, 445f, 17f)
        label(canvas, "한빛공원", 92f, 475f, 17f)
        label(canvas, "시민광장", 762f, 117f, 16f)
        label(canvas, "문화거리", 715f, 559f, 15f)
        label(canvas, "중앙로", 433f, 130f, 16f)
        label(canvas, "한빛천", 273f, 278f, 15f, if (day) color("#517D8A") else color("#82ABB8"))
        label(canvas, "시청", 916f, 337f, 15f)
        // 차량 위치 표시. 시스템 계기판·속도 화면과 따로 그린다
        p.color = if (day) color("#B8D4F3") else color("#355E86")
        canvas.drawCircle(534f, 507f, 40f, p)
        p.color = Color.WHITE
        canvas.drawCircle(534f, 507f, 25f, p)
        fill(canvas, floatArrayOf(534f, 484f, 551f, 522f, 534f, 515f, 517f, 522f), color("#2869C9"))
        if (hazard) {
            p.color = if (day) color("#FAE4C5") else color("#5C462F")
            canvas.drawCircle(575f, 312f, 38f, p)
            p.color = color("#C67A25")
            canvas.drawCircle(575f, 312f, 24f, p)
            label(canvas, "!", 570f, 323f, 30f, Color.WHITE)
        }
        // 축척은 가상 지도에만 맞춘다
        p.color = if (day) color("#607279") else color("#A8B8C4")
        p.strokeWidth = 2f
        canvas.drawLine(115f, 620f, 195f, 620f, p)
        canvas.drawLine(115f, 615f, 115f, 625f, p)
        canvas.drawLine(195f, 615f, 195f, 625f, p)
        label(canvas, "가상 지도", 115f, 606f, 14f)
        canvas.restore()
    }
}

class CockpitIcon(context: Context, private val kind: String, private val tint: Int) :
    View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.save()
        c.translate(width * .18f, height * .18f)
        c.scale(width * .64f / 24, height * .64f / 24)
        p.color = tint
        path.reset()
        when (kind) {
            "photo" -> {
                c.drawRoundRect(2f, 6f, 22f, 21f, 2f, 2f, p)
                c.drawCircle(12f, 13f, 4f, p)
                path.moveTo(6f, 6f)
                path.lineTo(8f, 3f)
                path.lineTo(16f, 3f)
                path.lineTo(18f, 6f)
                c.drawPath(path, p)
            }

            "video" -> {
                c.drawRoundRect(2f, 4f, 22f, 21f, 2f, 2f, p)
                path.moveTo(9f, 8f)
                path.lineTo(16f, 12.5f)
                path.lineTo(9f, 17f)
                path.close()
                c.drawPath(path, p)
            }

            "pin" -> {
                path.moveTo(12f, 23f)
                path.cubicTo(9f, 18f, 4f, 13f, 4f, 9f)
                path.cubicTo(4f, -1f, 20f, -1f, 20f, 9f)
                path.cubicTo(20f, 13f, 15f, 18f, 12f, 23f)
                c.drawPath(path, p)
                c.drawCircle(12f, 8f, 3f, p)
            }

            "signal" -> {
                path.moveTo(1f, 13f)
                path.lineTo(6f, 13f)
                path.lineTo(9f, 4f)
                path.lineTo(13f, 21f)
                path.lineTo(17f, 10f)
                path.lineTo(20f, 13f)
                path.lineTo(23f, 13f)
                c.drawPath(path, p)
            }

            "clock" -> {
                c.drawCircle(12f, 12f, 10f, p)
                c.drawLine(12f, 5f, 12f, 12f, p)
                c.drawLine(12f, 12f, 17f, 15f, p)
            }

            "check" -> {
                c.drawCircle(12f, 12f, 10f, p)
                c.drawLine(6f, 12f, 10f, 16f, p)
                c.drawLine(10f, 16f, 18f, 8f, p)
            }

            "cancel" -> {
                c.drawCircle(12f, 12f, 10f, p)
                c.drawLine(7f, 7f, 17f, 17f, p)
                c.drawLine(7f, 17f, 17f, 7f, p)
            }

            "홈", "shield" -> {
                path.moveTo(12f, 2f)
                path.lineTo(21f, 6f)
                path.lineTo(20f, 14f)
                path.quadTo(18f, 20f, 12f, 23f)
                path.quadTo(6f, 20f, 4f, 14f)
                path.lineTo(3f, 6f)
                path.close()
                c.drawPath(path, p)
                c.drawLine(8f, 12f, 11f, 15f, p)
                c.drawLine(11f, 15f, 17f, 9f, p)
            }

            "도로" -> {
                c.drawLine(7f, 2f, 3f, 22f, p)
                c.drawLine(17f, 2f, 21f, 22f, p)
                c.drawLine(12f, 2f, 12f, 6f, p)
                c.drawLine(12f, 10f, 12f, 14f, p)
                c.drawLine(12f, 18f, 12f, 22f, p)
            }

            "차량" -> {
                path.moveTo(3f, 15f)
                path.lineTo(5f, 7f)
                path.quadTo(6f, 5f, 9f, 5f)
                path.lineTo(16f, 5f)
                path.lineTo(20f, 12f)
                path.lineTo(21f, 18f)
                path.lineTo(3f, 18f)
                path.close()
                c.drawPath(path, p)
                c.drawLine(5f, 12f, 19f, 12f, p)
                c.drawCircle(7f, 16f, 1f, p)
                c.drawCircle(17f, 16f, 1f, p)
                c.drawLine(5f, 19f, 5f, 21f, p)
                c.drawLine(19f, 19f, 19f, 21f, p)
            }

            "기록" -> {
                c.drawRoundRect(5f, 2f, 20f, 22f, 2f, 2f, p)
                c.drawLine(9f, 7f, 16f, 7f, p)
                c.drawLine(9f, 12f, 16f, 12f, p)
                c.drawLine(9f, 17f, 14f, 17f, p)
            }

            "설정" -> {
                c.drawCircle(12f, 12f, 7f, p)
                c.drawCircle(12f, 12f, 2.5f, p)
                for (i in 0..7) {
                    val a = i * PI / 4
                    c.drawLine(
                        (12 + 7 * cos(a)).toFloat(),
                        (12 + 7 * sin(a)).toFloat(),
                        (12 + 10 * cos(a)).toFloat(),
                        (12 + 10 * sin(a)).toFloat(),
                        p
                    )
                }
            }

            "mic" -> {
                c.drawRoundRect(9f, 2f, 15f, 15f, 3f, 3f, p)
                path.moveTo(5f, 11f)
                path.cubicTo(5f, 23f, 19f, 23f, 19f, 11f)
                c.drawPath(path, p)
                c.drawLine(12f, 20f, 12f, 23f, p)
            }

            "alert" -> {
                path.moveTo(12f, 2f)
                path.lineTo(23f, 21f)
                path.lineTo(1f, 21f)
                path.close()
                c.drawPath(path, p)
                c.drawLine(12f, 9f, 12f, 14f, p)
                c.drawCircle(12f, 18f, .6f, p)
            }

            "sun" -> {
                c.drawCircle(12f, 12f, 4f, p)
                for (i in 0..7) {
                    val a = i * PI / 4
                    c.drawLine(
                        (12 + 7 * cos(a)).toFloat(),
                        (12 + 7 * sin(a)).toFloat(),
                        (12 + 10 * cos(a)).toFloat(),
                        (12 + 10 * sin(a)).toFloat(),
                        p
                    )
                }
            }
        }
        c.restore()
    }
}

class SignalChartView(context: Context, private val samples: JSONArray, private val day: Boolean) :
    View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Path()
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val left = 48f
        val top = 24f
        val w = width - 64f
        val h = height - 62f
        p.textSize = 24f
        p.typeface = PleosTypography.face()
        for (v in 11..15) {
            val y = top + (15 - v) / 4f * h
            p.color = if (day) Color.rgb(218, 226, 232) else Color.rgb(55, 70, 82)
            p.strokeWidth = 1f
            c.drawLine(left, y, left + w, y, p)
            p.color = if (day) Color.rgb(84, 103, 115) else Color.rgb(170, 185, 197)
            c.drawText("$v", 4f, y + 7, p)
        }
        line.reset()
        for (i in 0 until samples.length()) {
            val s = samples.getJSONObject(i)
            val x = left + i.toFloat() / max(1, samples.length() - 1) * w
            val y = top + (15 - s.optDouble("voltage", 13.9)).toFloat() / 4 * h
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = if (day) Color.rgb(53, 109, 197) else Color.rgb(145, 192, 255)
        c.drawPath(line, p)
        p.style = Paint.Style.FILL
        p.textSize = 22f
        p.color = if (day) Color.rgb(84, 103, 115) else Color.rgb(170, 185, 197)
        c.drawText("0 s", left, height - 8f, p)
        c.drawText("30 s", left + w - 48, height - 8f, p)
    }
}
