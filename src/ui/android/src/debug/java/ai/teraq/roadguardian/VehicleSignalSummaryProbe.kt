package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject

/** Synthetic display-boundary cases, separate from actual SDK observations. */
class VehicleSignalSummaryProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            fun fixture() = JSONObject().put("gear", "GEAR_PARK").put("fresh_gear_received", true)
                .put("vehicle_speed", JSONObject().put("fresh", true).put("kilometers_per_hour", 0))
                .put(
                    "wipers",
                    JSONObject().put(
                        "FRONT_WINDSHIELD",
                        JSONObject().put("fresh", true).put("state", "OFF")
                    )
                )
                .put(
                    "airbags",
                    JSONObject().put(
                        "seats",
                        JSONObject().put(
                            "driver",
                            JSONObject().put("fresh", true).put("deployed", false)
                        )
                    )
                )

            val fresh = VehicleSignalSummary.from(fixture())
            check(fresh.gear == "P" && fresh.speed == "0" && fresh.wiper == "꺼짐 응답" && fresh.airbag == "조회 좌석 미전개")
            val stale = fixture().put("fresh_gear_received", false)
            stale.getJSONObject("vehicle_speed").put("fresh", false)
            stale.getJSONObject("wipers").getJSONObject("FRONT_WINDSHIELD").put("fresh", false)
            val missing = VehicleSignalSummary.from(stale)
            check(missing.gear == "—" && missing.speed == "—" && missing.wiper == "미확인")
            val absent = VehicleSignalSummary.from(JSONObject())
            check(absent.speed == "—" && absent.airbag == "미확인" && absent.tires.none { it })
            val unknownWiper = fixture()
            unknownWiper.getJSONObject("wipers").getJSONObject("FRONT_WINDSHIELD")
                .put("state", "UNKNOWN")
            check(VehicleSignalSummary.from(unknownWiper).wiper == "미확인")
            val partial = fixture()
            partial.getJSONObject("airbags").getJSONObject("seats")
                .put("passenger", JSONObject().put("fresh", false).put("deployed", false))
            check(VehicleSignalSummary.from(partial).airbag == "미확인")
            partial.getJSONObject("airbags").getJSONObject("seats").getJSONObject("driver")
                .put("deployed", true)
            check(VehicleSignalSummary.from(partial).deployed)
            partial.getJSONObject("airbags").getJSONObject("seats").getJSONObject("driver")
                .put("fresh", false)
            check(!VehicleSignalSummary.from(partial).deployed)
            val malformed = fixture()
            malformed.getJSONObject("airbags").getJSONObject("seats").put("unknown_seat", "invalid")
            check(VehicleSignalSummary.from(malformed).airbag == "미확인")
            val invalid = fixture()
            invalid.getJSONObject("vehicle_speed").put("kilometers_per_hour", "NaN")
            check(VehicleSignalSummary.from(invalid).speed == "—")
            val thresholdOnly = fixture().put(
                "tires",
                JSONObject().put(
                    "wheels",
                    JSONObject().put(
                        "LEFT_FRONT",
                        JSONObject().put(
                            "critical_threshold",
                            JSONObject().put("fresh", true).put("value", 137)
                        )
                    )
                )
            )
            check(VehicleSignalSummary.from(thresholdOnly).tires.none { it })
            result.putString(
                "stream",
                "PASS: synthetic summary boundaries: fresh zero, stale values, absent data, partial airbag coverage, fresh/stale deployment, nonfinite speed, threshold-only pressure. No real sensor accuracy or road test.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
