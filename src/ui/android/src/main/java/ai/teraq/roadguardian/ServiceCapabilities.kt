package ai.teraq.roadguardian

import org.json.JSONObject

/** 기능 안내 값. 실제 기록·메모 요청의 권한 확인을 대신하지 않는다 */
data class ServiceCapabilities(
    val known: Boolean,
    val vehicleAllowed: Boolean,
    val records: Boolean,
    val notesRead: Boolean,
    val notesWrite: Boolean,
    val observationsRead: Boolean = false,
    val observationsWrite: Boolean = false,
    val ownerCasesCreate: Boolean = false,
    val photosRead: Boolean = false,
    val photosWrite: Boolean = false,
    val photosExport: Boolean = false,
    val powertrainRead: Boolean = false,
    val measurementsRead: Boolean = false,
    val measurementsWrite: Boolean = false,
    val measurementsExport: Boolean = false
) {
    companion object {
        val legacy = ServiceCapabilities(false, false, true, false, false)
        fun parse(response: String): ServiceCapabilities {
            try {
                val data = JSONObject(response)
                require(data.getString("schema") == "road-guardian.capabilities.v1")
                val version = data.get("api_version")
                if (version is Int && version != 1) throw AccountAccessException(
                    AccountAccessException.Reason.INCOMPATIBLE
                )
                require(version is Int && version == 1)
                val access = data.getString("vehicle_access")
                require(access in setOf("allowed", "not_granted"))
                val features = data.getJSONObject("features")
                fun flag(key: String): Boolean {
                    val value = features.get(key)
                    require(value is Boolean)
                    return value
                }

                val records = flag("case_pages")
                val detail = flag("case_detail")
                val video = flag("case_video")
                val export = flag("evidence_export")
                val read = flag("private_notes_read")
                val write = flag("private_notes_write")
                fun optionalFlag(key: String) = if (features.has(key)) flag(key) else false
                val observeRead = optionalFlag("sdk_observations_read")
                val observeWrite = optionalFlag("sdk_observations_write")
                val create = optionalFlag("owner_cases_create")
                val photosRead = optionalFlag("case_photos_read")
                val photosWrite = optionalFlag("case_photos_write")
                val photosExport = optionalFlag("case_photos_export")
                val powertrain = optionalFlag("powertrain_read")
                val measurementsRead = optionalFlag("measurement_runs_read")
                val measurementsWrite = optionalFlag("measurement_runs_write")
                val measurementsExport = optionalFlag("measurement_runs_export")
                require(!measurementsWrite || measurementsRead)
                require(!measurementsExport || (measurementsRead && export))
                require(!write || read)
                require(!observeWrite || observeRead)
                require(!photosWrite || photosRead)
                require(!photosExport || (photosRead && export))
                require(access == "allowed" || !(records || detail || video || export || read || write || observeRead || observeWrite || create || photosRead || photosWrite || photosExport || powertrain || measurementsRead || measurementsWrite || measurementsExport))
                return ServiceCapabilities(
                    true,
                    access == "allowed",
                    records && detail,
                    read,
                    write,
                    observeRead,
                    observeWrite,
                    create,
                    photosRead,
                    photosWrite,
                    photosExport,
                    powertrain,
                    measurementsRead,
                    measurementsWrite,
                    measurementsExport
                )
            } catch (_: org.json.JSONException) {
                throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
            } catch (_: IllegalArgumentException) {
                throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
            }
        }
    }
}
