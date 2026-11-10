package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 로컬 사진만 보여 준다. 화면에서 떨어진 뷰에는 늦게 읽힌 이미지를 넣지 않는다 */
// 화면은 플랫폼 Activity와 테마(API 28 이상)를 쓴다. AppCompat 색조·벡터 지원은 쓰지 않는다
@android.annotation.SuppressLint("AppCompatCustomView")
open class ParkingPhotoView(context: Context, private val maxEdge: Int = 1600) :
    ImageView(context) {
    companion object {
        private val workers =
            ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16)).apply {
                allowCoreThreadTimeOut(true)
            }
        private val main = Handler(Looper.getMainLooper())
    }

    private var file: File? = null
    private var generation = 0
    private var task: FutureTask<Unit>? = null
    private var notice = ""
    private val noticePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16 * resources.displayMetrics.scaledDensity
    }

    init {
        scaleType = ScaleType.FIT_CENTER
        setBackgroundColor(Color.rgb(12, 14, 17))
    }

    fun load(value: File) {
        cancel()
        file = value
        setImageDrawable(null)
        notice = "사진을 불러오는 중입니다."
        invalidate()
        if (isAttachedToWindow) startLoad()
    }

    private fun cancel() {
        generation++
        task?.let {
            it.cancel(true)
            workers.remove(it)
        }
        task = null
    }

    private fun startLoad() {
        val source = file ?: return
        val current = generation
        val work = FutureTask<Unit> {
            val bitmap = try {
                decode(source, maxEdge)
            } catch (_: Exception) {
                null
            }
            main.post {
                if (current != generation || !isAttachedToWindow) bitmap?.recycle()
                else {
                    task = null
                    setImageBitmap(bitmap)
                    notice = if (bitmap == null) "사진을 열지 못했습니다." else ""
                    invalidate()
                }
            }
        }
        task = work
        try {
            workers.execute(work)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            task = null
            notice = "사진을 다시 열어 주세요."
            invalidate()
        }
    }

    protected open fun decode(source: File, edge: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            val factor =
                (maxOf(info.size.width, info.size.height).toFloat() / edge).coerceAtLeast(1f)
            decoder.setTargetSize(
                (info.size.width / factor).toInt().coerceAtLeast(1),
                (info.size.height / factor).toInt().coerceAtLeast(1)
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (file != null) startLoad()
    }

    override fun onDetachedFromWindow() {
        cancel()
        setImageDrawable(null)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (notice.isNotEmpty()) canvas.drawText(
            notice,
            width / 2f,
            height / 2f - (noticePaint.ascent() + noticePaint.descent()) / 2,
            noticePaint
        )
    }
}
