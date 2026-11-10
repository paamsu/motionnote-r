package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors

/** 같은 사건의 확인된 합성 참고 사진 한 장을 보여 준다 */
class EventEvidencePhotoPanel(
    context: Context,
    private val ui: PhoneAccountUi,
    private val event: JSONObject,
    private val client: BackendClient?,
    private val allowed: () -> Boolean,
    private val alignWithMap: Boolean = false,
    private val comparisonLabel: String? = null,
    private val allowStaticRoadReference: Boolean = false,
    private val onValidation: (Boolean) -> Unit = {},
    private val onPhotoHeight: (Int) -> Unit = {},
    private val fitPhotoAspect: Boolean = false,
    private val externalStatus: android.widget.TextView? = null
) : LinearLayout(context) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var worker: java.util.concurrent.ExecutorService? = null
    private var generation = 0L
    private var bitmap: Bitmap? = null
    private val photo = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = alignWithMap || fitPhotoAspect
        maxHeight = ui.dp(560)
        contentDescription = "이 사건 기록에 보관된 사진"
        setBackgroundColor(PleosPalette.surface(ui.light))
        visibility = GONE
    }
    private val unavailable =
        DriverEventGraphic(context, "review", ui.light).apply { visibility = GONE }
    private val representative = event.optJSONObject("assessment")?.optString("kind") == "pothole"
    private val caption = ui.text(
        comparisonLabel ?: if (representative) "대표 사진" else "현장 사진",
        if (alignWithMap) 22f else 18f
    ).apply {
        gravity = Gravity.CENTER
        visibility = GONE
    }
    private val status =
        ui.text("사진 확인 중", ui.evidenceCaptionSize, true).apply { gravity = Gravity.CENTER }

    private fun clear() {
        status.visibility = if (externalStatus == null) VISIBLE else GONE
        unavailable.visibility = GONE
        photo.setImageDrawable(null)
        photo.visibility = GONE
        caption.visibility = GONE
        bitmap?.recycle()
        bitmap = null
    }

    private fun showUnavailable(message: String) {
        clear()
        status.text = message
        onValidation(false)
        if (allowed()) {
            unavailable.visibility = VISIBLE
            unavailable.contentDescription = message
            if (alignWithMap) caption.visibility = VISIBLE
        }
    }

    private fun stop() {
        generation++
        worker?.shutdownNow()
        worker = null
        clear()
    }

    private val watch = object : Runnable {
        override fun run() {
            if (!allowed()) {
                stop()
                status.text = "사진 표시 종료"
            }
            if (isAttachedToWindow) postDelayed(this, 300)
        }
    }

    init {
        orientation = VERTICAL
        if (externalStatus != null) {
            externalStatus.text = status.text
            status.visibility = GONE
            status.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    externalStatus.text = s
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        if (alignWithMap) addView(caption)
        addView(
            photo,
            LayoutParams(
                -1,
                if (alignWithMap || fitPhotoAspect) -2 else ui.dp(if (resources.configuration.screenWidthDp >= 760) 280 else 220)
            )
        )
        addView(
            unavailable,
            LayoutParams(
                -1,
                ui.dp(if (comparisonLabel != null) 124 else if (alignWithMap) 260 else if (resources.configuration.screenWidthDp >= 760) 280 else 220)
            )
        )
        if (!alignWithMap) addView(caption)
        addView(status, LayoutParams(-1, -2).apply { if (alignWithMap) topMargin = ui.dp(12) })
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (photo.visibility == VISIBLE && photo.height > 0) onPhotoHeight(photo.height)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stop()
        post(watch)
        if (!allowed() || client == null) {
            showUnavailable("사진 확인 필요")
            return
        }
        if (event.optString("status") == "dismissed" && event.opt("evidence_valid") == true) {
            clear()
            status.text = "검토에서 제외한 기록"
            onValidation(false)
            return
        }
        val meta = try {
            metadata(event, allowStaticRoadReference)
        } catch (_: Exception) {
            showUnavailable("사진 확인 실패")
            return
        }
        if (meta == null) {
            showUnavailable("사진 미첨부")
            return
        }
        status.text = "사진 확인 중"
        val backend = requireNotNull(client)
        val ticket = ++generation
        worker = Executors.newSingleThreadExecutor()
        worker?.execute {
            val result = runCatching {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val bytes = backend.scenePhoto(event.getString("id"))
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                require(bytes.size == meta.getInt("bytes") && sha(bytes) == meta.getString("sha256"))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(
                    bounds.outMimeType == meta.getString("mime") && bounds.outWidth == meta.getInt(
                        "width"
                    ) && bounds.outHeight == meta.getInt("height")
                )
                val fresh = JSONObject(backend.request("/api/events/${event.getString("id")}"))
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val current = checkNotNull(metadata(fresh, allowStaticRoadReference))
                require(
                    fresh.getString("id") == event.getString("id") && fresh.getString("hash") == event.getString(
                        "hash"
                    )
                )
                for (key in listOf(
                    "source",
                    "camera_evidence",
                    "event_id",
                    "sensor_sha256",
                    "sha256",
                    "bytes",
                    "mime",
                    "width",
                    "height"
                )) require(meta.get(key) == current.get(key))
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                checkNotNull(
                    BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample })
                )
            }
            val posted = main.post {
                if (ticket != generation || !isAttachedToWindow || !allowed()) {
                    result.getOrNull()?.recycle()
                    return@post
                }
                result.fold(
                    onSuccess = {
                        clear()
                        onValidation(true)
                        bitmap = it
                        photo.setImageBitmap(it)
                        photo.visibility = VISIBLE
                        caption.visibility = if (externalStatus == null) VISIBLE else GONE
                        if (comparisonLabel != null) status.visibility = GONE
                        status.text = if (representative) "대표 사진 보관 완료" else "기록 확보 완료"
                    },
                    onFailure = { showUnavailable("사진 확인 실패") })
            }
            if (!posted) result.getOrNull()?.recycle()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(watch)
        stop()
        super.onDetachedFromWindow()
    }

    companion object {
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun metadata(event: JSONObject, allowStaticRoadReference: Boolean = false): JSONObject? {
            require(
                event.opt("evidence_valid") == true && event.optString("source") == "synthetic" && event.optString(
                    "status"
                ) != "dismissed"
            )
            val kind = event.getJSONObject("assessment").getString("kind")
            require(
                kind == "parking" ||
                        (VideoLaneAnalysis.repeatedCandidate(event) && event.optJSONObject("video")
                            ?.optString("input_kind") == "authored_lane_scene") ||
                        (WitnessCasePanel.eligible(event) && event.optJSONObject("video")
                            ?.optString("input_kind") == "static_photo_encoded_as_video") ||
                        (kind == "crash" && event.optJSONObject("video")
                            ?.optString("input_kind") in setOf(
                            "static_photo_encoded_as_video",
                            "authored_collision_scene"
                        )) ||
                        (kind == "violation" && event.optJSONObject("video")
                            ?.optString("input_kind") in setOf(
                            "authored_stationary_scene",
                            "static_photo_encoded_as_video"
                        )) ||
                        (kind in setOf("observation", "pothole") && (event.optJSONObject("video")
                            ?.optString("input_kind") == "authored_road_scene" || (allowStaticRoadReference && event.optJSONObject(
                            "video"
                        )?.optString("input_kind") == "static_photo_encoded_as_video"))) ||
                        (kind == "wanted" && event.optJSONObject("video")
                            ?.optString("input_kind") in setOf(
                            "authored_moving_plate_scene",
                            "static_photo_encoded_as_video"
                        ))
            )
            if (!event.has("video") || event.isNull("video")) return null
            val video = event.getJSONObject("video")
            if (!video.has("input_photo") || video.isNull("input_photo")) return null
            require(video.get("integrity_valid") == true && video.getString("source") == "synthetic_scene_fixture")
            val meta = video.getJSONObject("input_photo")
            require(
                meta.getString("source") == "synthetic_scene_fixture" && meta.get("camera_evidence") == false && meta.get(
                    "integrity_valid"
                ) == true
            )
            require(
                meta.getString("event_id") == event.getString("id") && meta.getString("sensor_sha256") == event.getString(
                    "hash"
                )
            )
            require(
                Regex("[0-9a-f]{64}").matches(meta.getString("sha256")) && Regex("[0-9a-f]{64}").matches(
                    meta.getString("sensor_sha256")
                )
            )
            require(meta.getString("mime") in setOf("image/png", "image/jpeg"))
            for ((key, limit) in listOf(
                "bytes" to 8 * 1024 * 1024,
                "width" to 4096,
                "height" to 4096
            )) {
                val value = meta.get(key)
                require(value is Int && value in 1..limit)
            }
            return meta
        }
    }
}
