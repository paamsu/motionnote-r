package ai.teraq.roadguardian

import android.content.Context
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.concurrent.Executors

/** 연결된 기록의 참고 사진 두 장. 해시를 각각 확인한다 */
class RoadComparisonPhotos(
    context: Context,
    private val ui: PhoneAccountUi,
    private val event: JSONObject,
    private val client: BackendClient,
    private val allowed: () -> Boolean,
    private val onValidation: (Boolean?) -> Unit = {}
) : LinearLayout(context) {
    private var worker: java.util.concurrent.ExecutorService? = null
    private var generation = 0

    init {
        orientation = HORIZONTAL
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val ticket = ++generation
        onValidation(null)
        removeAllViews()
        val status = ui.text("전후 사진 확인 중", 20f)
        addView(status)
        worker = Executors.newSingleThreadExecutor()
        worker?.execute {
            val result = runCatching {
                require(allowed())
                val link = event.getJSONObject("road_followup_case")
                val id = link.getString("followup_id")
                require(Regex("[a-f0-9-]{36}").matches(id))
                val child = JSONObject(client.request("/api/events/$id"))
                val fresh = JSONObject(client.request("/api/events/${event.getString("id")}"))
                val current = fresh.getJSONObject("road_followup_case")
                require(
                    fresh.optBoolean("evidence_valid") && fresh.getString("hash") == event.getString(
                        "hash"
                    )
                )
                require(
                    current.getString("followup_id") == id && current.getString("followup_sha256") == link.getString(
                        "followup_sha256"
                    )
                )
                require(current.getString("stage") == link.getString("stage"))
                require(child.getString("id") == id && child.getString("hash") == link.getString("followup_sha256"))
                require(
                    EventEvidencePhotoPanel.metadata(
                        fresh,
                        true
                    ) != null && EventEvidencePhotoPanel.metadata(child, true) != null
                )
                fresh to child
            }
            post {
                if (ticket != generation || !isAttachedToWindow || !allowed()) return@post
                result.fold(onSuccess = { (first, second) ->
                    removeAllViews()
                    orientation =
                        if (resources.configuration.screenWidthDp >= 760) HORIZONTAL else VERTICAL
                    val verified = arrayOfNulls<Boolean>(2)
                    listOf(
                        first to "최초 관측",
                        second to "이후 관측"
                    ).forEachIndexed { index, (record, label) ->
                        addView(
                            EventEvidencePhotoPanel(
                            context,
                            ui,
                            record,
                            client,
                            allowed,
                            alignWithMap = true,
                            comparisonLabel = label,
                            allowStaticRoadReference = true,
                            onValidation = { valid ->
                                verified[index] = valid
                                onValidation(if (verified.any { it == false }) false else if (verified.all { it == true }) true else null)
                            }),
                            if (orientation == HORIZONTAL) LayoutParams(
                                0,
                                -2,
                                1f
                            ).apply { if (index == 0) marginEnd = ui.dp(16) } else LayoutParams(
                                -1,
                                -2
                            ))
                    }
                }, onFailure = {
                    status.text = "전후 사진 확인 필요"
                    onValidation(false)
                })
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
