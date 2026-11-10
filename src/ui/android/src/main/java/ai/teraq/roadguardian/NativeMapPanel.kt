package ai.teraq.roadguardian

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.style.expressions.Expression.*

/** 공개 지도 타일 위에 합성 위치(표시 있음)나 확인된 기기 위치를 보여 준다 */
class NativeMapPanel(
    context: Context,
    day: Boolean,
    hazardPosition: LatLng?,
    driving: Boolean,
    vehiclePosition: LatLng = LatLng(37.499, 127.003),
    positionLabel: String = "가상 차량·사건 위치",
    private val synthetic: Boolean = true,
    private val onSnapshot: ((android.graphics.Bitmap) -> Unit)? = null,
    private val onFailure: (() -> Unit)? = null
) : FrameLayout(context) {
    private val mapView: MapView
    private var ended = false
    private var snapshotRequested = false
    private var position = vehiclePosition
    private var positionText = positionLabel
    private var nativeMap: org.maplibre.android.maps.MapLibreMap? = null
    private var positionSource: GeoJsonSource? = null
    private lateinit var label: TextView
    private var mapFailed = false
    private var fullyRendered = false
    private var navigationAnchor = false

    /** 알림 아래로 지도 카메라 중심을 옮긴다. 실제 지리 좌표는 그대로다 */
    fun useNavigationAnchor() {
        navigationAnchor = true
        applyNavigationAnchor()
    }

    private fun applyNavigationAnchor() {
        val map = nativeMap ?: return
        if (!navigationAnchor || ended || mapFailed || mapView.height <= 0) return
        // 여백을 준 화면의 중심은 y=.72h다: (h + .44h)/2
        // 여백은 투영만 바꾼다. GeoJSON 마커 좌표나 화면 오버레이는 바꾸지 않는다
        fullyRendered = false
        map.cameraPosition = CameraPosition.Builder(map.cameraPosition).target(position)
            .padding(0.0, mapView.height * 0.44, 0.0, 0.0).build()
    }

    /** 타일이 다 그려진 뒤에만 지도를 캡처한다. 대체 그림은 캡처하지 않는다 */
    fun captureDisplayedMap(onReady: (android.graphics.Bitmap) -> Unit): Boolean {
        val map = nativeMap ?: return false
        if (ended || mapFailed || !fullyRendered || !isAttachedToWindow) return false
        map.snapshot { image ->
            if (ended || mapFailed || !isAttachedToWindow) image.recycle() else onReady(image)
        }
        return true
    }

    private val snapshotDeadline = Runnable { failLoading() }
    private fun failLoading() {
        if (ended || mapFailed) return
        mapFailed = true
        removeCallbacks(snapshotDeadline)
        updateLabel()
        mapView.visibility = GONE
        post { if (!ended) onFailure?.invoke() }
    }

    fun updatePosition(fix: VehicleLocationSample): Boolean {
        if (ended || synthetic || !fix.usable(android.os.SystemClock.elapsedRealtime())) return false
        position = LatLng(fix.latitude, fix.longitude)
        positionText = "${if (fix.mock) "시험 GPS" else "기기 GPS"} · 정확도 약 ${fix.accuracy.toInt()} m"
        positionSource?.setGeoJson(Point.fromLngLat(position.longitude, position.latitude))
        nativeMap?.let {
            it.cameraPosition = CameraPosition.Builder(it.cameraPosition).target(position).build()
        }
        updateLabel()
        return true
    }

    private fun updateLabel() {
        label.text = when {
            mapFailed -> if (synthetic) "지도 연결 실패 · 가상 도로 표시" else "지도 연결 실패 · $positionText"
            positionSource == null -> "지도 불러오는 중 · $positionText"
            else -> "$positionText · 지도 OpenFreeMap / © OpenStreetMap"
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        MapLibre.getInstance(context)
        if (synthetic) addView(
            SafetyMapView(context, day, hazardPosition != null),
            LayoutParams(-1, -1)
        )
        else setBackgroundColor(if (day) Color.rgb(236, 239, 236) else Color.rgb(20, 22, 25))
        mapView = MapView(context)
        mapView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyNavigationAnchor()
        }
        addView(mapView, LayoutParams(-1, -1))
        label = TextView(context).apply {
            text = "지도 불러오는 중 · $positionLabel"
            textSize = 16f
            setTextColor(if (day) Color.DKGRAY else Color.WHITE)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setBackgroundColor(if (day) 0xEDF5F7FA.toInt() else 0xED202D38.toInt())
        }
        addView(label, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(42)
        })
        if (driving) {
            mapView.importantForAccessibility =
                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(android.view.View(context).apply {
                setOnTouchListener { _, _ -> true }
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(-1, -1))
        }
        mapView.addOnDidFinishRenderingMapListener { fully ->
            fullyRendered = fully && positionSource != null
            if (fully && mapView.width > 0 && mapView.height > 0 && !ended && positionSource != null && !snapshotRequested && onSnapshot != null) {
                snapshotRequested = true
                // 첫 완성 프레임에도 지명 글자가 아직 흐려지는 중일 수 있다
                postDelayed({
                    if (!ended && !mapFailed) nativeMap?.snapshot { image ->
                        if (!ended && !mapFailed) {
                            removeCallbacks(snapshotDeadline)
                            onSnapshot.invoke(image)
                        } else image.recycle()
                    }
                }, 500)
            }
        }
        mapView.onCreate(null)
        mapView.addOnDidFailLoadingMapListener { failLoading() }
        mapView.getMapAsync { map ->
            if (ended || mapFailed) return@getMapAsync
            nativeMap = map
            map.uiSettings.apply {
                isCompassEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isScrollGesturesEnabled = !driving
                isZoomGesturesEnabled = !driving
                isLogoEnabled = false
                attributionGravity = Gravity.BOTTOM or Gravity.START
            }
            map.cameraPosition =
                CameraPosition.Builder().target(position).zoom(15.5).tilt(0.0).build()
            applyNavigationAnchor()
            map.setStyle(
                Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
            ) { style ->
                if (ended || mapFailed) return@setStyle
                for (layer in style.layers) {
                    if (layer is FillExtrusionLayer || layer.id.startsWith("poi_") || layer.id in listOf(
                            "highway-name-path",
                            "road_one_way_arrow",
                            "road_one_way_arrow_opposite"
                        )
                    ) layer.setProperties(visibility("none"))
                    if (layer is SymbolLayer && !layer.id.contains("shield")) layer.setProperties(
                        textField(coalesce(get("name:ko"), get("name:nonlatin"), get("name"))),
                        textFont(arrayOf("Noto Sans Regular")), textLetterSpacing(0f),
                        textColor(if (day) "#5A6A75" else "#C6D1DA"), textHaloWidth(1.5f)
                    )
                    if (day) when (layer) {
                        is BackgroundLayer -> layer.setProperties(backgroundColor("#ECEFEC"))
                        is FillLayer -> layer.setProperties(
                            fillColor(
                                when {
                                    layer.id == "water" -> "#BAD6DF"
                                    layer.id.contains("park") || layer.id.contains("wood") || layer.id.contains(
                                        "grass"
                                    ) -> "#D3DFCC"

                                    layer.id == "building" -> "#D5DCD9"
                                    else -> "#E5E9E3"
                                }
                            )
                        )
                    }
                }
                if (!day) for (layer in style.layers) {
                    when (layer) {
                        is BackgroundLayer -> layer.setProperties(backgroundColor("#22262A"))
                        is FillLayer -> layer.setProperties(
                            fillColor(
                                when {
                                    layer.id.contains("water") -> "#233F51"
                                    layer.id.contains("park") || layer.id.contains("wood") -> "#293D37"
                                    layer.id.contains("building") -> "#33404B"
                                    else -> "#282E32"
                                }
                            )
                        )

                        is LineLayer -> layer.setProperties(
                            lineColor(
                                if (layer.id.contains("motorway") || layer.id.contains(
                                        "trunk"
                                    )
                                ) "#6E7F8F" else "#465865"
                            )
                        )

                        is SymbolLayer -> layer.setProperties(
                            textColor("#C6D1DA"),
                            textHaloColor("#25313A")
                        )
                    }
                }
                positionSource = GeoJsonSource(
                    "vehicle-position",
                    Point.fromLngLat(position.longitude, position.latitude)
                ).also { style.addSource(it) }
                val marker = android.graphics.Bitmap.createBitmap(
                    80,
                    80,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(marker)
                val arrow = android.graphics.Path().apply {
                    moveTo(40f, 6f)
                    lineTo(66f, 69f)
                    lineTo(40f, 57f)
                    lineTo(14f, 69f)
                    close()
                }
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    this.style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 8f
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }
                canvas.drawPath(arrow, paint)
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = Color.parseColor("#3789F4")
                canvas.drawPath(arrow, paint)
                style.addImage("vehicle-direction", marker)
                if (synthetic) style.addLayer(
                    SymbolLayer(
                        "demo-position-arrow",
                        "vehicle-position"
                    ).withProperties(
                        iconImage("vehicle-direction"),
                        iconSize(.65f),
                        iconRotate(90f),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true)
                    )
                )
                else style.addLayer(
                    CircleLayer(
                        "vehicle-position-dot",
                        "vehicle-position"
                    ).withProperties(
                        circleColor("#3789F4"),
                        circleRadius(9f),
                        circleStrokeColor("#FFFFFF"),
                        circleStrokeWidth(3f)
                    )
                )
                if (hazardPosition != null) {
                    style.addSource(
                        GeoJsonSource(
                            "demo-hazard",
                            Point.fromLngLat(hazardPosition.longitude, hazardPosition.latitude)
                        )
                    )
                    style.addLayer(
                        CircleLayer("demo-hazard-dot", "demo-hazard").withProperties(
                            circleColor("#C37A27"),
                            circleRadius(12f),
                            circleStrokeColor("#FFFFFF"),
                            circleStrokeWidth(3f)
                        )
                    )
                }
                updateLabel()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mapView.onStart()
        mapView.onResume()
        // 타일 요청이 끝나지 않아도 사건 지도 캡처는 로딩 상태를 벗어나야 한다
        if (onSnapshot != null && onFailure != null) postDelayed(snapshotDeadline, 12_000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(snapshotDeadline)
        if (!ended) {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
            ended = true
        }
        super.onDetachedFromWindow()
    }
}
