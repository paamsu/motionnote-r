package ai.teraq.roadguardian

import org.json.JSONArray
import org.json.JSONObject

/** 근거를 복사할 때 숫자 타입을 유지한다. JSONObject.toString은 .0을 지운다 */
object EvidenceJson {
    private class Budget {
        var nodes = 0
        fun visit(depth: Int) {
            require(depth <= 64 && ++nodes <= 200_000) { "Evidence JSON bounds" }
        }
    }

    fun copy(value: JSONObject): JSONObject = copyValue(value, 0, Budget()) as JSONObject
    private fun copyValue(value: Any?, depth: Int, budget: Budget): Any {
        budget.visit(depth)
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> JSONObject().also { out ->
                value.keys().forEach { k -> out.put(k, copyValue(value.get(k), depth + 1, budget)) }
            }

            is JSONArray -> JSONArray().also { out ->
                for (i in 0 until value.length()) out.put(
                    copyValue(value.get(i), depth + 1, budget)
                )
            }

            is String, is Boolean, is Int, is Long, is Short, is Byte -> value
            is Double -> value.also { require(it.isFinite()) }
            is Float -> value.also { require(it.isFinite()) }
            else -> error("Unsupported evidence JSON value")
        }
    }

    /** 새 스냅숏에만 적용된다. 예전 스냅숏에서 잃은 타입은 되살릴 수 없다 */
    fun encode(value: JSONObject): String {
        val out = StringBuilder()
        val budget = Budget()
        fun write(v: Any?, depth: Int) {
            budget.visit(depth)
            when (v) {
                null, JSONObject.NULL -> out.append("null")
                is JSONObject -> {
                    out.append('{')
                    var first = true
                    v.keys().forEach { k ->
                        if (!first) out.append(',')
                        first = false
                        out.append(JSONObject.quote(k)).append(':')
                        write(v.get(k), depth + 1)
                    }
                    out.append('}')
                }

                is JSONArray -> {
                    out.append('[')
                    for (i in 0 until v.length()) {
                        if (i > 0) out.append(',')
                        write(v.get(i), depth + 1)
                    }
                    out.append(']')
                }

                is String -> out.append(JSONObject.quote(v))
                is Boolean, is Int, is Long, is Short, is Byte -> out.append(v.toString())
                is Double -> {
                    require(v.isFinite())
                    out.append(v.toString())
                }

                is Float -> {
                    require(v.isFinite())
                    out.append(v.toString())
                }

                else -> error("Unsupported evidence JSON value")
            }
            require(out.length <= 8 * 1024 * 1024) { "Evidence JSON size" }
        }
        write(value, 0)
        return out.toString()
    }
}
