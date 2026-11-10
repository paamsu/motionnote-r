package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.util.AtomicFile
import android.util.Base64
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/** 저장된 사건 좌표를 앱 지도에 고정해 보여 준다 */
class EventLocationPanel(
    context: Context, private val ui: PhoneAccountUi, private val event: JSONObject,
    private val compactCaption: Boolean = false, private val allowed: () -> Boolean
) : LinearLayout(context) {
    private val point = requireNotNull(location(event))
    private val frame = android.widget.FrameLayout(context)
    private val address = ui.text("주소 확인 중", ui.evidenceCaptionSize, true).apply {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
    }
    private val caption = ui.text("사건 위치", 22f).apply { gravity = Gravity.CENTER }
    private var map: NativeMapPanel? = null
    private var bitmap: Bitmap? = null
    private var worker: java.util.concurrent.ExecutorService? = null
    private var generation = 0
    private var savedThemeMatches = false
    private var navigationCapture = false
    private var addressText = "주소 확인 불가"
    private var addressDone = false
    private var addressSource = "unavailable"
    private val target =
        File(context.filesDir, "event-map/${event.getString("id")}-${event.getString("hash")}.json")
    private val watch = object : Runnable {
        override fun run() {
            if (!allowed()) {
                generation++
                frame.removeAllViews()
                bitmap?.recycle()
                bitmap = null
                address.text = "위치 표시 종료"
            }
            if (isAttachedToWindow) postDelayed(this, 300)
        }
    }

    init {
        orientation = VERTICAL
        addView(caption)
        addView(
            frame,
            LayoutParams(
                -1,
                ui.dp(if (resources.configuration.screenWidthDp in 760..1099 && resources.configuration.fontScale >= 1.3f) 220 else 260)
            )
        )
        addView(address, LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        addView(
            ui.text("© OpenFreeMap · OpenStreetMap", 12f).apply { gravity = Gravity.END },
            LayoutParams(-1, -2)
        )
    }

    fun useCompactFrame() {
        frame.layoutParams = LayoutParams(-1, ui.dp(200))
    }

    fun fillRowHeight(alignPhotoHeaders: Boolean = false) {
        if (alignPhotoHeaders) {
            // 주변 사진 위 방향 표시와 같은 글자 크기를 쓴다
            addView(ui.text("", 22f, true).apply { visibility = INVISIBLE }, 1)
            (address.layoutParams as LayoutParams).topMargin = 0
        }
        frame.layoutParams =
            if (alignPhotoHeaders) LayoutParams(-1, ui.dp(420)) else LayoutParams(-1, 0, 1f)
    }

    fun matchPhotoHeight(height: Int) {
        // 사진 콜백은 레이아웃 중에 실행되므로 다시 측정은 그 뒤로 미룬다
        if (height > 0 && frame.layoutParams.height != height) frame.post {
            if (isAttachedToWindow && frame.layoutParams.height != height)
                frame.layoutParams = LayoutParams(-1, height)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(watch)
        if (!allowed()) {
            address.text = "위치 확인 필요"
            return
        }
        val ticket = ++generation
        worker = Executors.newSingleThreadExecutor()
        worker?.execute {
            val saved = runCatching {
                val navigation = ContestNavigationSnapshot.file(context, event)
                val source = if (navigation.exists()) navigation else target
                val record = JSONObject(AtomicFile(source).readFully().toString(Charsets.UTF_8))
                require(record.getString("schema") == "road-guardian.event-map.v1")
                if (source == navigation) require(record.getString("source") == ContestNavigationSnapshot.SOURCE)
                require(
                    record.getString("event_id") == event.getString("id") && record.getString("event_hash") == event.getString(
                        "hash"
                    )
                )
                require(record.getDouble("lat") == point.latitude && record.getDouble("lon") == point.longitude)
                val bytes = Base64.decode(record.getString("png"), Base64.NO_WRAP)
                require(bytes.size <= 8 * 1024 * 1024 && sha(bytes) == record.getString("image_sha256"))
                val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                record to decoded
            }.getOrNull()
            if (saved != null) {
                post {
                    if (ticket != generation || !isAttachedToWindow || !allowed()) {
                        saved.second.recycle()
                        return@post
                    }
                    navigationCapture =
                        saved.first.optString("source") == ContestNavigationSnapshot.SOURCE
                    savedThemeMatches =
                        saved.first.has("light_theme") && saved.first.optBoolean("light_theme") == ui.light
                    addressText = saved.first.getString("address")
                        .let { if (it == "주소 확인 불가") displayAddress(event) ?: it else it }
                    address.text = addressText
                    addressDone = true
                    showImage(saved.second)
                    caption.text = "사건 위치"
                    frame.post { refreshUndersizedMap(ticket) }
                }
                return@execute
            }
            post { loadMap(ticket) }
            val stored = displayAddress(event)

            @Suppress("DEPRECATION")
            val found = stored ?: runCatching {
                Geocoder(
                    context,
                    Locale.KOREAN
                ).getFromLocation(point.latitude, point.longitude, 1)?.firstOrNull()
                    ?.getAddressLine(0)
            }.getOrNull()
            post {
                if (ticket == generation && isAttachedToWindow && allowed()) {
                    addressText = found?.takeIf { it.isNotBlank() } ?: "주소 확인 불가"
                    addressSource =
                        if (storedAddress(event) != null) "scenario_fixture" else if (stored != null) "scenario_catalog" else if (found != null) "android_geocoder" else "unavailable"
                    addressDone = true
                    address.text = addressText
                    save()
                }
            }
        }
    }

    private fun loadMap(ticket: Int) {
        if (ticket != generation || !isAttachedToWindow || !allowed()) return
        frame.removeAllViews()
        map = null
        caption.text = "사건 위치"
        map = NativeMapPanel(
            context,
            ui.light,
            point,
            true,
            point,
            "사건 위치",
            false,
            onSnapshot = { image ->
                if (ticket != generation || !isAttachedToWindow || !allowed()) image.recycle() else {
                    showImage(image)
                    caption.text = "사건 위치"
                    save()
                }
            },
            onFailure = {
                if (ticket == generation && isAttachedToWindow && allowed()) {
                    frame.removeAllViews()
                    map = null
                    val retry = LinearLayout(context).apply {
                        orientation = VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24))
                        addView(
                            ui.text("지도를 불러오지 못했습니다", 22f).apply { gravity = Gravity.CENTER },
                            LayoutParams(-1, -2)
                        )
                        addView(
                            ui.button("지도 다시 불러오기") { loadMap(ticket) },
                            LayoutParams(-1, ui.dp(80)).apply { topMargin = ui.dp(24) })
                    }
                    frame.addView(retry, android.widget.FrameLayout.LayoutParams(-1, -1))
                }
            }).also { frame.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)) }
    }

    /** 표시만 새로 그린다. 원래 사건 지도 기록은 바꾸지 않는다 */
    private fun refreshUndersizedMap(ticket: Int) {
        val saved = bitmap ?: return
        if (navigationCapture) return // Keep the captured frame even if the display theme changes.
        if (ticket != generation || !isAttachedToWindow || !allowed() || frame.width <= 0 || frame.height <= 0) return
        val scale =
            maxOf(frame.width.toFloat() / saved.width, frame.height.toFloat() / saved.height)
        if (scale <= 1.05f && savedThemeMatches) return
        map = NativeMapPanel(
            context,
            ui.light,
            point,
            true,
            point,
            "사건 위치",
            false,
            onSnapshot = { image ->
                if (ticket != generation || !isAttachedToWindow || !allowed()) image.recycle() else {
                    showImage(image)
                    frame.getChildAt(0).contentDescription = "사건 좌표 지도 · 현재 렌더링"
                    caption.text = "사건 위치"
                    // save()를 부르지 않는다. 사건 당시 지도 이미지가 아니기 때문이다
                }
            },
            onFailure = {
                if (ticket == generation && isAttachedToWindow) {
                    map?.let { frame.removeView(it) }
                    map = null
                    caption.text = "사건 위치"
                }
            }).also { frame.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)) }
    }

    private fun showImage(image: Bitmap) {
        frame.removeAllViews()
        map = null
        bitmap?.recycle()
        bitmap = image
        frame.addView(ImageView(context).apply {
            setImageBitmap(image)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = if (navigationCapture) "알림 당시 지도 스냅샷" else "사건 좌표 기준 지도 스냅샷"
        }, android.widget.FrameLayout.LayoutParams(-1, -1))
    }

    private fun save() {
        val image = bitmap ?: return
        if (!addressDone || !allowed()) return
        val bytes =
            ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        val record = JSONObject().put("schema", "road-guardian.event-map.v1")
            .put("source", "app_map_render_at_event_coordinates")
            .put("event_id", event.getString("id")).put("event_hash", event.getString("hash"))
            .put("event_created", event.opt("created"))
            .put("snapshot_created", System.currentTimeMillis() / 1000.0)
            .put("lat", point.latitude).put("lon", point.longitude).put("address", addressText)
            .put("address_source", addressSource)
            .put("light_theme", ui.light).put("image_sha256", sha(bytes))
            .put("png", Base64.encodeToString(bytes, Base64.NO_WRAP))
        worker?.execute {
            val atomic = AtomicFile(target)
            var stream: java.io.FileOutputStream? = null
            try {
                target.parentFile?.mkdirs()
                stream = atomic.startWrite()
                stream.write(record.toString().toByteArray())
                atomic.finishWrite(stream)
                post { if (isAttachedToWindow && allowed()) caption.text = "사건 위치" }
            } catch (_: Exception) {
                stream?.let { atomic.failWrite(it) }
                post { if (isAttachedToWindow) caption.text = "사건 위치 · 지도 저장 실패" }
            }
        }
    }

    override fun onDetachedFromWindow() {
        generation++
        removeCallbacks(watch)
        worker?.shutdownNow()
        worker = null
        frame.removeAllViews()
        bitmap?.recycle()
        bitmap = null
        super.onDetachedFromWindow()
    }

    companion object {
        /** 예전 시연 지점의 표시용 이름. 원래 근거는 그대로 둔다 */
        fun displayAddress(event: JSONObject): String? {
            storedAddress(event)?.let { return it }
            if (!(BuildConfig.DEBUG || BuildConfig.CONTEST_DEMO) || !BuildConfig.SYNTHETIC_BACKEND || location(
                    event
                ) == null
            ) return null
            val loc = event.getJSONObject("data").getJSONObject("location")
            // 명시적으로 잘못되었거나 출처가 다른 주소는 바꾸지 않는다
            if (loc.has("address_label") || loc.has("address_source")) return null
            return if (loc.optString("road_id") == "demo-road-east" &&
                loc.getDouble("lat") == 37.5 && loc.getDouble("lon") == 127.0
            )
                "서울특별시 서초구 사평대로 인근" else null
        }

        fun storedAddress(event: JSONObject): String? {
            if (location(event) == null) return null
            val loc = event.optJSONObject("data")?.optJSONObject("location") ?: return null
            return loc.optString("address_label").trim().takeIf {
                it.isNotBlank() && it != "null" && it.length <= 160 && loc.optString("address_source") == "scenario_fixture"
            }
        }

        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun location(event: JSONObject): LatLng? {
            if (event.optString("source") != "synthetic" || event.opt("evidence_valid") != true || event.optString(
                    "status"
                ) == "dismissed"
            ) return null
            if (!Regex("[a-zA-Z0-9-]{1,80}").matches(event.optString("id")) || !Regex("[0-9a-f]{64}").matches(
                    event.optString("hash")
                )
            ) return null
            val loc = event.optJSONObject("data")?.optJSONObject("location") ?: return null
            val lat = (loc.opt("lat") as? Number)?.toDouble() ?: return null
            val lon = (loc.opt("lon") as? Number)?.toDouble() ?: return null
            return if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) LatLng(
                lat,
                lon
            ) else null
        }
    }
}
