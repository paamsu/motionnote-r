package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** No UX restrictions are bypassed: exercises only private response state transitions. */
class VehicleRecordStateProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val state = VehicleRecordState()
            val page = AccountPageResponse.Page(
                JSONArray().put(
                    JSONObject().put(
                        "id",
                        "synthetic-record"
                    )
                ), "next"
            )
            val first = state.begin()
            val roadRequest = state.begin(nextCategory = "road")
            state.previous.add(null)
            val maintenanceRequest = state.begin(nextCategory = "maintenance")
            check(state.previous.isEmpty() && state.category == "maintenance")
            check(
                !state.accept(
                    roadRequest,
                    true,
                    page = page
                )
            ) { "Old category response accepted" }
            check(state.accept(maintenanceRequest, true, page = page))
            val current = state.begin()
            check(state.loading && state.requested)
            check(state.accept(current, true, page = page))
            check(state.page === page && !state.loading)
            val detail = state.begin()
            check(state.page == null)
            check(!state.accept(first, true, page = page)) { "Superseded page accepted" }
            check(state.accept(detail, true, detail = JSONObject().put("plate", "TEST ONLY")))
            state.clear()
            check(state.detail == null && state.page == null && !state.requested)
            check(!state.accept(detail, true, detail = JSONObject())) { "Hidden response accepted" }
            val resumed = state.begin()
            check(!state.accept(resumed, false, page = page)) { "Restricted response accepted" }
            check(state.page == null && !state.loading && !state.requested)
            val error = state.begin("next")
            check(state.accept(error, true, error = "Synthetic unavailable"))
            check(state.error == "Synthetic unavailable" && state.page == null && state.cursor == "next")
            state.previous.add(null)
            state.clear()
            check(state.previous.isEmpty() && state.cursor == null && state.error == null)
            val expiring = state.begin()
            check(
                state.accept(
                    expiring,
                    true,
                    page = page,
                    lease = AccountDisplayLease(100000, 50000, 1000)
                )
            )
            check(!state.expire(69999, 20999))
            check(state.expire(70000, 21000))
            check(state.page == null && state.detail == null && state.requested && state.error != null)
            check(!state.accept(expiring, true, page = page)) { "Expired response resurrected" }
            val absent = state.begin()
            check(state.accept(absent, true, page = page))
            check(state.expire(50000, 1000)) { "Missing lease allowed display" }
            if (BuildConfig.OIDC_ISSUER.isBlank()) {
                val failure =
                    runCatching { AuthenticatedEventsClient(targetContext).page(null) }.exceptionOrNull()
                check(failure is AccountAccessException && failure.reason == AccountAccessException.Reason.NOT_CONFIGURED)
            }
            result.putString(
                "stream",
                "PASS: list/detail state; superseded/hidden/restricted replies rejected; expiry clears data and blocks resurrection; missing lease fails closed. No actual login, network or vehicle UI tested.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
