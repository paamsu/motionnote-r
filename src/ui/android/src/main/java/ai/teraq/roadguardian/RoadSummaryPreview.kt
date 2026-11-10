package ai.teraq.roadguardian

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.concurrent.Executors

/** 기존 출처 주소에서 읽는 미리보기. 관측을 합치거나 고치지 않는다 */
class RoadSummaryPreview(
    context: Context, private val ui: PhoneAccountUi, private val groupId: String,
    private val client: BackendClient, private val allowed: () -> Boolean
) : LinearLayout(context) {
    private var worker: java.util.concurrent.ExecutorService? = null
    private var generation = 0

    init {
        orientation = VERTICAL
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!allowed()) return
        val ticket = ++generation
        addView(ui.text("관측 사진 확인 중", 22f).apply { gravity = Gravity.CENTER })
        worker = Executors.newSingleThreadExecutor()
        worker?.execute {
            val result = runCatching {
                require(groupId.matches(Regex("[0-9a-f]{16}")))
                val page = JSONObject(client.request("/api/demo/road-summary/groups/$groupId"))
                require(
                    page.optString("schema") == "road-guardian.demo-road-group.v1" && page.optString(
                        "group_id"
                    ) == groupId
                )
                require(
                    page.optString("source") == "synthetic" && page.opt("external_submission") == false && page.opt(
                        "repair_verified"
                    ) == false
                )
                val events = page.getJSONArray("events")
                require(events.length() in 1..10 && page.optInt("offset", -1) == 0)
                val selected =
                    (0 until minOf(3, events.length())).map { events.getJSONObject(it) }.reversed()
                require(selected.map { it.getString("id") }.distinct().size == selected.size)
                selected.forEach {
                    require(AccountEventRoute.checked(it.getString("id")) == it.getString("id"))
                    require(it.optString("source") == "synthetic" && it.opt("evidence_valid") == true)
                    require(it.optString("status") !in setOf("dismissed", "pending_confirmation"))
                    require(it.optJSONObject("assessment")?.optString("kind") == "pothole")
                }
                val bundle = RoadAgencyReceiptStore.collect(client, groupId)
                require(page.getString("membership_sha256") == bundle.getString("membership_sha256"))
                val sources = bundle.getJSONArray("sources")
                selected.forEach { event ->
                    val source = (0 until sources.length()).map { sources.getJSONObject(it) }
                        .single { it.getString("event_id") == event.getString("id") }
                    require(source.getString("sensor_sha256") == event.getString("hash"))
                    for (key in listOf(
                        "data",
                        "video",
                        "surround_views",
                        "surround"
                    )) require(
                        (source.opt(key) ?: JSONObject.NULL).toString() == (event.opt(key)
                            ?: JSONObject.NULL).toString()
                    )
                }
                selected to bundle
            }
            post {
                if (ticket != generation || !isAttachedToWindow || !allowed()) return@post
                removeAllViews()
                val collected = result.getOrNull()
                if (collected == null) {
                    addView(ui.text("사진 확인 필요 · 원본 기록에서 확인", 22f))
                    return@post
                }
                val events = collected.first
                EventLocationPanel.displayAddress(events.last())?.let { address ->
                    addView(
                        ui.text(address, ui.evidenceCaptionSize, true)
                            .apply { gravity = Gravity.CENTER },
                        LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
                }
                val row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val photoWidth = (resources.configuration.screenWidthDp - 96).coerceIn(240, 640)
                row.gravity = Gravity.CENTER
                row.addView(
                    EventEvidencePhotoPanel(
                        context,
                        ui,
                        events.last(),
                        client,
                        allowed,
                        comparisonLabel = "대표 현장 사진",
                        allowStaticRoadReference = true,
                        fitPhotoAspect = true
                    ),
                    LayoutParams(ui.dp(photoWidth), -2)
                )
                addView(row, LayoutParams(-1, -2))
                addView(RoadAgencyPanel(ui, collected.second, allowed), LayoutParams(-1, -2))
            }
        }
    }

    override fun onDetachedFromWindow() {
        generation++
        worker?.shutdownNow()
        worker = null
        super.onDetachedFromWindow()
    }
}
