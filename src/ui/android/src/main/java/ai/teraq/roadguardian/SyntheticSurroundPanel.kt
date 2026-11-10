package ai.teraq.roadguardian

import android.graphics.*
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors

/** 보존된 묶음 그림이나 작성된 사진 네 장을 보여 준다 */
class SyntheticSurroundPanel(
    context: android.content.Context,
    private val ui: PhoneAccountUi,
    private val event: JSONObject,
    private val client: BackendClient?,
    private val allowed: () -> Boolean
) : LinearLayout(context) {
    var fillWidth = false
    var compactPresentation = false
    var externalStatus: android.widget.TextView? = null
        set(value) {
            field = value
            if (value != null) {
                value.text = status.text
                status.visibility = GONE
                status.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) {
                    }

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                        externalStatus?.text = s
                    }

                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
        }
    var onPhotoHeight: (Int) -> Unit = {}
    private var bitmap: Bitmap? = null
    private var separate: List<Bitmap?> = emptyList()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val grid = object : LinearLayout(context) {
        override fun onMeasure(w: Int, h: Int) {
            val bound = if (fillWidth) View.MeasureSpec.getSize(w) else minOf(
                View.MeasureSpec.getSize(w),
                ui.dp(840)
            )
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(bound, View.MeasureSpec.EXACTLY), h)
        }
    }.apply { orientation = VERTICAL }
    private var generation = 0L
    private var worker: java.util.concurrent.ExecutorService? = null
    private val status =
        ui.text("", ui.evidenceCaptionSize, true).apply { gravity = Gravity.CENTER }
    private val caption = ui.text("주변 사진", 22f).apply {
        gravity = Gravity.CENTER
        visibility = GONE
    }
    private val photos = mutableListOf<View>()
    private val fallback = DriverEventGraphic(
        context,
        if (ScenePhotoAnalysis.summary(event)?.title in setOf(
                "사진 판독 보류",
                "사진 판독 확인 필요",
                "노면 확인 필요"
            )
        ) "review" else "pothole",
        ui.light
    )

    private fun showPhotos(ready: Boolean) {
        grid.visibility = if (ready) VISIBLE else GONE
        caption.visibility = if (ready && !compactPresentation) VISIBLE else GONE
        fallback.visibility = if (ready) GONE else VISIBLE
    }

    private val watch = object : Runnable {
        override fun run() {
            if (!allowed()) {
                generation++
                status.text = "사진 표시 종료"
                worker?.shutdownNow()
                worker = null
                clear()
                showPhotos(false)
                fallback.visibility = GONE
            }
            if (isAttachedToWindow) postDelayed(this, 300)
        }
    }

    init {
        orientation = VERTICAL
        addView(fallback, LayoutParams(-1, ui.dp(170)))
        addView(caption)
        addView(grid, LayoutParams(-1, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        for (row in 0..1) {
            val pair = LinearLayout(context)
            for (col in 0..1) {
                val cell = LinearLayout(context).apply { orientation = VERTICAL }
                val direction = listOf("전방", "후방", "왼쪽", "오른쪽")[row * 2 + col]
                cell.addView(ui.text(direction, 22f, true).apply { gravity = Gravity.CENTER })
                val photo = object : View(context) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    override fun onMeasure(w: Int, h: Int) {
                        val width = MeasureSpec.getSize(w)
                        val individual = separate.getOrNull(row * 2 + col)
                        val height = if (event.optJSONObject("surround_views") != null) {
                            val ratio =
                                individual?.let { it.height.toFloat() / it.width } ?: 2f / 3f
                            (width * ratio).toInt().coerceIn(ui.dp(80), ui.dp(360))
                        } else minOf((width * 9f / 16f).toInt(), ui.dp(180))
                        setMeasuredDimension(width, height)
                    }

                    override fun onDraw(c: Canvas) {
                        val individual = separate.getOrNull(row * 2 + col)
                        val b = individual ?: bitmap
                        if (b != null && !b.isRecycled) {
                            val source = if (individual != null) Rect(
                                0,
                                0,
                                b.width,
                                b.height
                            ) else Rect(
                                col * b.width / 2,
                                row * b.height / 2,
                                (col + 1) * b.width / 2,
                                (row + 1) * b.height / 2
                            )
                            val destination = if (individual != null) {
                                val scale =
                                    minOf(width.toFloat() / b.width, height.toFloat() / b.height)
                                val w = (b.width * scale).toInt()
                                val h = (b.height * scale).toInt()
                                c.drawColor(PleosPalette.surface(ui.light))
                                Rect(
                                    (width - w) / 2,
                                    (height - h) / 2,
                                    (width + w) / 2,
                                    (height + h) / 2
                                )
                            } else Rect(0, 0, width, height)
                            c.drawBitmap(b, source, destination, paint)
                        } else {
                            c.drawColor(PleosPalette.surface(ui.light))
                            paint.color = ui.muted
                            paint.textSize = ui.dp(18).toFloat()
                            paint.textAlign = Paint.Align.CENTER
                            c.drawText(
                                if (separate.isNotEmpty()) "사진 없음" else "사진 확인 대기",
                                width / 2f,
                                height / 2f,
                                paint
                            )
                        }
                    }
                }.apply { contentDescription = "$direction 합성 시야" }
                photos.add(photo)
                cell.addView(photo, LayoutParams(-1, -2))
                pair.addView(
                    cell,
                    LayoutParams(0, -2, 1f).apply {
                        setMargins(
                            0,
                            0,
                            if (col == 0) ui.dp(12) else 0,
                            if (row == 0) ui.dp(8) else 0
                        )
                    })
            }
            grid.addView(pair)
        }
        addView(status)
        grid.visibility = GONE
        status.text = initialStatus()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (grid.visibility == VISIBLE && photos.size == 4) {
            val first = Rect(0, 0, photos.first().width, photos.first().height)
            val last = Rect(0, 0, photos.last().width, photos.last().height)
            offsetDescendantRectToMyCoords(photos.first(), first)
            offsetDescendantRectToMyCoords(photos.last(), last)
            if (last.bottom > first.top) onPhotoHeight(last.bottom - first.top)
        }
    }

    private fun sensorStored() =
        event.opt("evidence_valid") == true && event.optString("status") != "dismissed"

    private fun initialStatus() =
        if (!sensorStored()) "기록 확인 필요" else if (event.optJSONObject("surround") == null && event.optJSONObject(
                "surround_views"
            ) == null
        ) if (ScenePhotoAnalysis.present(event)) "센서 기록 보관 · 주변 사진 미첨부" else "센서 기록 보관 · 사진 미첨부" else "센서 기록 보관 · 사진 확인 중"

    private fun clear() {
        val old = bitmap
        bitmap = null
        val oldSeparate = separate
        separate = emptyList()
        photos.forEach { it.invalidate() }
        old?.recycle()
        oldSeparate.forEach { it?.recycle() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showPhotos(false)
        status.text = initialStatus()
        post(watch)
        val views = try {
            SyntheticSurroundViews.metadata(event)
        } catch (failure: Exception) {
            status.text = if (sensorStored()) "센서 기록 보관 · 사진 확인 실패" else "기록 확인 필요"
            return
        }
        if (views != null) {
            loadSeparate(views)
            return
        }
        val meta = event.optJSONObject("surround") ?: return
        if (client == null || !allowed() || event.opt("evidence_valid") != true || event.optString("status") == "dismissed") {
            grid.visibility = GONE
            status.text = if (sensorStored()) "센서 기록 보관 · 사진 확인 필요" else "기록 확인 필요"
            return
        }
        val backend = requireNotNull(client)
        val ticket = ++generation
        worker = Executors.newSingleThreadExecutor()
        worker?.execute {
            var sensorStillValid = sensorStored()
            val result = runCatching {
                validate(event, meta)
                val bytes = backend.surround(event.getString("id"))
                require(
                    bytes.size == meta.getInt("bytes") && MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { "%02x".format(it) } == meta.getString("sha256")
                )
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                require(
                    options.outMimeType == "image/png" && options.outWidth == meta.getInt("width") && options.outHeight == meta.getInt(
                        "height"
                    )
                )
                // 사진 전송 뒤 연결을 다시 읽고 나서 성공을 표시한다
                val fresh = JSONObject(backend.request("/api/events/${event.getString("id")}"))
                sensorStillValid =
                    fresh.opt("evidence_valid") == true && fresh.optString("status") != "dismissed" && fresh.optString(
                        "id"
                    ) == event.optString("id") && fresh.optString("hash") == event.optString("hash")
                val current = fresh.getJSONObject("surround")
                validate(fresh, current)
                require(
                    fresh.getString("id") == event.getString("id") && fresh.getString("hash") == event.getString(
                        "hash"
                    ) && current.getString("sha256") == meta.getString("sha256") && fresh.optString(
                        "status"
                    ) != "dismissed"
                )
                requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
            val posted = main.post {
                if (ticket != generation || !isAttachedToWindow || !allowed()) {
                    result.getOrNull()?.recycle()
                    return@post
                }
                result.fold(onSuccess = {
                    bitmap = it
                    showPhotos(true)
                    photos.forEach { v -> v.invalidate() }
                    status.text = "기록 확보 완료"
                }, onFailure = {
                    if (BuildConfig.DEBUG) android.util.Log.w(
                        "SurroundPhoto",
                        "Synthetic image verification failed",
                        it
                    )
                    clear()
                    showPhotos(false)
                    status.text = if (sensorStillValid) "센서 기록 보관 · 사진 확인 실패" else "기록 확인 필요"
                })
            }
            if (!posted) result.getOrNull()?.recycle()
        }
    }

    private fun missingLabels(rows: List<JSONObject>) =
        SyntheticSurroundViews.directions.withIndex()
            .filter { entry -> rows.none { it.getString("direction") == entry.value } }
            .joinToString("·") { listOf("전방", "후방", "왼쪽", "오른쪽")[it.index] }

    private fun loadSeparate(meta: JSONObject) {
        if (client == null || !allowed() || !sensorStored() || event.optString("source") != "synthetic") {
            status.text = if (sensorStored()) "센서 기록 보관 · 사진 확인 필요" else "기록 확인 필요"
            return
        }
        val rows = runCatching {
            SyntheticSurroundViews.validate(
                meta,
                event.getString("id"),
                event.getString("hash")
            )
        }.getOrElse {
            status.text = "센서 기록 보관 · 사진 확인 실패"
            return
        }
        if (meta.getString("status") == "unavailable") {
            status.text = "센서 기록 보관 · 사진 확인 실패"
            return
        }
        val backend = requireNotNull(client)
        val ticket = ++generation
        worker = Executors.newSingleThreadExecutor()
        worker?.execute {
            val decoded = mutableListOf<Bitmap>()
            var sensorStillValid = sensorStored()
            val result = runCatching {
                rows.forEach { row ->
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Photo display cancelled")
                    val bytes =
                        backend.surroundView(event.getString("id"), row.getString("direction"))
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Photo display cancelled")
                    decoded.add(SyntheticSurroundViews.decode(row, bytes, maxDimension = 1024))
                }
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Photo display cancelled")
                val fresh = JSONObject(backend.request("/api/events/${event.getString("id")}"))
                sensorStillValid =
                    fresh.opt("evidence_valid") == true && fresh.optString("status") != "dismissed" && fresh.optString(
                        "source"
                    ) == "synthetic" && fresh.optString("id") == event.optString("id") && fresh.optString(
                        "hash"
                    ) == event.optString("hash")
                require(sensorStillValid)
                val current = SyntheticSurroundViews.validate(
                    fresh.getJSONObject("surround_views"),
                    fresh.getString("id"),
                    fresh.getString("hash")
                )
                require(
                    fresh.getJSONObject("surround_views").getString("status") == meta.getString(
                        "status"
                    ) && SyntheticSurroundViews.same(rows, current)
                )
            }
            if (result.isFailure) {
                decoded.forEach { it.recycle() }
                decoded.clear()
            }
            val posted = main.post {
                if (ticket != generation || !isAttachedToWindow || !allowed()) {
                    decoded.forEach { it.recycle() }
                    return@post
                }
                if (result.isSuccess) {
                    clear()
                    separate = SyntheticSurroundViews.directions.map { direction ->
                        rows.indexOfFirst {
                            it.getString("direction") == direction
                        }.let { if (it >= 0) decoded[it] else null }
                    }
                    caption.text = "주변 사진"
                    showPhotos(true)
                    photos.forEach {
                        it.requestLayout()
                        it.invalidate()
                    }
                    status.text = if (rows.size == 4) "기록 확보 완료" else "사진 ${rows.size}개 확보 · ${
                        missingLabels(rows)
                    } 사진 없음"
                } else {
                    clear()
                    showPhotos(false)
                    status.text = if (sensorStillValid) "센서 기록 보관 · 사진 확인 실패" else "기록 확인 필요"
                }
            }
            if (!posted) decoded.forEach { it.recycle() }
        }
    }

    override fun onDetachedFromWindow() {
        generation++
        removeCallbacks(watch)
        worker?.shutdownNow()
        worker = null
        clear()
        super.onDetachedFromWindow()
    }

    companion object {
        fun validate(event: JSONObject, meta: JSONObject) {
            require(event.opt("evidence_valid") == true && event.optString("source") == "synthetic")
            require(
                meta.getString("schema") == "road-guardian.synthetic-surround.v1" && meta.getString(
                    "source"
                ) == "synthetic" && meta.get("camera_evidence") == false && meta.get("integrity_valid") == true
            )
            require(
                meta.getString("event_id") == event.getString("id") && meta.getString("sensor_sha256") == event.getString(
                    "hash"
                )
            )
            require(
                meta.getString("url") == "/api/events/${event.getString("id")}/surround" && meta.getString(
                    "mime"
                ) == "image/png"
            )
            require(
                meta.getInt("bytes") in 1..8 * 1024 * 1024 && meta.getInt("width") in 2..4096 && meta.getInt(
                    "height"
                ) in 2..4096
            )
            val directions = listOf("front", "rear", "left", "right")
            val values = meta.getJSONArray("directions")
            require(values.length() == 4)
            val regions = meta.getJSONObject("regions")
            require(regions.length() == 4)
            directions.forEachIndexed { i, d ->
                require(values.getString(i) == d)
                val r = regions.getJSONArray(d)
                require(r.length() == 4)
                val expected = listOf((i % 2) * .5, (i / 2) * .5, .5, .5)
                for (j in 0..3) require(r.getDouble(j) == expected[j])
            }
        }
    }
}
