package ai.teraq.roadguardian

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 문서 저장소에 쓰기 전에 고른 두 파일을 풀지 않고 검사한다 */
object MeasurementPackageVerifier {
    private val names = setOf(
        "authored-original.json",
        "before.json",
        "after.json",
        "comparison.json",
        "report.json",
        "README.txt",
        "manifest.json"
    )

    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun verify(
        file: File,
        eventId: String,
        caseHash: String,
        vehicle: String,
        beforeId: String,
        afterId: String
    ): MeasurementProtocol.Comparison {
        try {
            require(file.length() in 1..3 * 1024 * 1024)
            val found = mutableMapOf<String, ByteArray>()
            var total = 0
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(!entry.isDirectory && entry.name in names && entry.name !in found && entry.size in 1..1024 * 1024)
                    val output = ByteArrayOutputStream()
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(count > 0 && total + count <= 3 * 1024 * 1024 && output.size() + count <= 1024 * 1024)
                            output.write(buffer, 0, count)
                            total += count
                        }
                    }
                    require(output.size().toLong() == entry.size)
                    found[entry.name] = output.toByteArray()
                }
            }
            require(found.keys == names)
            fun json(name: String) =
                MeasurementProtocol.decode(checkNotNull(found[name]), 1024 * 1024)

            val manifest = json("manifest.json")
            require(
                manifest.getString("schema") == "road-guardian.measurement-package.v1" && manifest.getString(
                    "event_id"
                ) == eventId
            )
            val entries = manifest.getJSONArray("files")
            require(entries.length() == 6)
            val listed = mutableSetOf<String>()
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val name = entry.getString("path")
                require(name != "manifest.json" && name in names && listed.add(name))
                val bytes = checkNotNull(found[name])
                val size = entry.get("bytes")
                require(
                    size is Number && size.toLong()
                        .toDouble() == size.toDouble() && size.toLong() == bytes.size.toLong() && entry.getString(
                        "sha256"
                    ) == hash(bytes)
                )
            }
            require(listed == names - "manifest.json")
            val report = json("report.json")
            require(
                report.getString("schema") == "road-guardian.measurement-export.v1" && report.getString(
                    "event_id"
                ) == eventId && report.getString("case_sha256") == caseHash
            )
            require(report.getString("before_id") == beforeId && report.getString("after_id") == afterId)
            require(
                report.getString("scope") == "current_subject_only" && report.getString("provenance") == "reported_measurements_not_attested" && report.getString(
                    "hash_basis"
                ) == "canonical_validated_run_utf8"
            )
            for (flag in listOf(
                "sensor_original",
                "conditions_verified",
                "repair_confirmed",
                "diagnosis_performed",
                "external_submission",
                "photos_included",
                "notes_included"
            )) require(report.get(flag) == false)
            require(hash(checkNotNull(found["authored-original.json"])) == caseHash)
            val authored = json("authored-original.json")
            require(
                authored.getString("schema") == "road-guardian.owner-record.v1" && authored.getString(
                    "source"
                ) == "owner_reported" && authored.getString("category") == "maintenance" && authored.getString(
                    "vehicle_id"
                ) == vehicle
            )
            val before =
                MeasurementProtocol.receipt(json("before.json").toString(), eventId, vehicle)
            val after = MeasurementProtocol.receipt(json("after.json").toString(), eventId, vehicle)
            require(before.summary.id == beforeId && after.summary.id == afterId)
            return MeasurementProtocol.comparison(
                json("comparison.json").toString(),
                eventId,
                caseHash,
                before,
                after
            )
        } catch (_: Exception) {
            throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
        }
    }
}
