package ai.teraq.roadguardian

import android.content.Context
import net.openid.appauth.AuthState
import org.json.JSONArray

/** 호출한 작업 스레드에서 실행한다. 시연 데이터 대체나 토큰 자동 갱신은 없다 */
class AuthenticatedEventsClient(context: Context) {
    private val application = context.applicationContext as RoadGuardianApplication
    private val cache = context.applicationContext.cacheDir

    data class Video(val file: java.io.File, val synthetic: Boolean)
    data class DisplayResult<T>(
        val value: T,
        val lease: AccountDisplayLease,
        val sessionFingerprint: String = ""
    )

    private fun fingerprint(value: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun sessionFingerprint() = fingerprint(checkedState().jsonSerializeString())
    fun pageForDisplay(cursor: String?, category: String = "all") =
        forDisplay { page(cursor, category) }

    fun eventForDisplay(id: String) = forDisplay { event(id) }
    fun capabilitiesForDisplay() = forDisplay { capabilities() }
    fun measurementsForDisplay(id: String) = forDisplay {
        check(BuildConfig.MEASUREMENTS_ENABLED)
        val state = checkedState()
        MeasurementProtocol.list(
            AuthenticatedTransport().measurements(
                BuildConfig.AUTH_API_URL,
                id,
                state.accessToken
            ), id
        )
    }

    fun measurementForDisplay(id: String, run: String, vehicle: String) = forDisplay {
        check(BuildConfig.MEASUREMENTS_ENABLED)
        val state = checkedState()
        MeasurementProtocol.receipt(
            AuthenticatedTransport().measurement(
                BuildConfig.AUTH_API_URL,
                id,
                run,
                state.accessToken
            ), id, vehicle
        ).also { require(it.summary.id == run) }
    }

    fun canSaveMeasurements() =
        checkedState().lastTokenResponse?.scope?.split(" ")?.contains("events:measure") == true

    fun saveMeasurementForDisplay(
        id: String,
        vehicle: String,
        body: org.json.JSONObject,
        expectedSession: String
    ) = forDisplay {
        check(BuildConfig.MEASUREMENTS_ENABLED)
        if (sessionFingerprint() != expectedSession) throw AccountAccessException(
            AccountAccessException.Reason.CHANGED
        )
        if (!canSaveMeasurements()) throw AccountAccessException(AccountAccessException.Reason.FORBIDDEN)
        MeasurementProtocol.validate(body, vehicle)
        val state = checkedState()
        MeasurementProtocol.receipt(
            AuthenticatedTransport().saveMeasurement(
                BuildConfig.AUTH_API_URL,
                id,
                body.toString(),
                state.accessToken
            ), id, vehicle, body
        )
    }

    fun measurementComparisonForDisplay(
        id: String,
        caseHash: String,
        before: MeasurementProtocol.Receipt,
        after: MeasurementProtocol.Receipt
    ) = forDisplay {
        check(BuildConfig.MEASUREMENTS_ENABLED)
        val state = checkedState()
        MeasurementProtocol.comparison(
            AuthenticatedTransport().measurementComparison(
                BuildConfig.AUTH_API_URL,
                id,
                before.summary.id,
                after.summary.id,
                state.accessToken
            ), id, caseHash, before, after
        )
    }

    fun powertrainForDisplay(id: String, vehicleId: String, start: String, end: String) =
        forDisplay {
            check(BuildConfig.POWERTRAIN_ENABLED)
            PowertrainBatch.checkedDates(start, end)
            val state = checkedState()
            if (state.lastTokenResponse?.scope?.split(" ")
                    ?.contains("events:powertrain") != true
            ) throw AccountAccessException(AccountAccessException.Reason.FORBIDDEN)
            PowertrainBatch.parse(
                AuthenticatedTransport().powertrain(
                    BuildConfig.AUTH_API_URL,
                    id,
                    start,
                    end,
                    state.accessToken
                ), id, vehicleId, start, end
            )
        }

    private fun capabilities(): ServiceCapabilities {
        val state = checkedState()
        val snapshot = state.jsonSerializeString()
        val response = try {
            AuthenticatedTransport().capabilities(BuildConfig.AUTH_API_URL, state.accessToken)
        } catch (failure: AccountAccessException) {
            if (failure.reason == AccountAccessException.Reason.NOT_FOUND) null else throw failure
        }
        if (checkedState().jsonSerializeString() != snapshot) throw AccountAccessException(
            AccountAccessException.Reason.CHANGED
        )
        return if (response == null) ServiceCapabilities.legacy else ServiceCapabilities.parse(
            response
        )
    }

    fun canReviewNotes(): Boolean = BuildConfig.REVIEW_NOTES_ENABLED &&
            checkedState().lastTokenResponse?.scope?.split(" ")?.contains("events:review") == true

    fun sdkObservationsForDisplay(id: String) = forDisplay {
        val state = checkedState()
        SdkObservationRecord.parse(
            org.json.JSONObject(
                AuthenticatedTransport().sdkObservations(
                    BuildConfig.AUTH_API_URL,
                    id,
                    state.accessToken
                )
            ), id
        )
    }

    fun canObserve() =
        checkedState().lastTokenResponse?.scope?.split(" ")?.contains("events:observe") == true

    fun canAttachPhotos() =
        checkedState().lastTokenResponse?.scope?.split(" ")?.contains("events:photos") == true

    fun photosForDisplay(id: String) = forDisplay {
        val state = checkedState()
        CasePhoto.list(
            org.json.JSONObject(
                AuthenticatedTransport().photos(
                    BuildConfig.AUTH_API_URL,
                    id,
                    state.accessToken
                )
            ), id
        )
    }

    fun photoForDisplay(photo: CasePhoto) = forDisplay {
        val state = checkedState()
        photo.content(
            org.json.JSONObject(
                AuthenticatedTransport().photo(
                    BuildConfig.AUTH_API_URL,
                    photo.eventId,
                    photo.id,
                    state.accessToken
                )
            )
        )
    }

    fun savePhotoForDisplay(id: String, body: org.json.JSONObject, expectedSession: String) =
        forDisplay {
            if (sessionFingerprint() != expectedSession) throw AccountAccessException(
                AccountAccessException.Reason.CHANGED
            )
            val state = checkedState()
            if (state.lastTokenResponse?.scope?.split(" ")
                    ?.contains("events:photos") != true
            ) throw AccountAccessException(AccountAccessException.Reason.FORBIDDEN)
            val photo = CasePhoto.parse(
                org.json.JSONObject(
                    AuthenticatedTransport().savePhoto(
                        BuildConfig.AUTH_API_URL,
                        id,
                        body.toString(),
                        state.accessToken
                    )
                ), id, body.getString("request_id")
            )
            require(
                photo.caption == body.getString("caption") && photo.uploadSha256 == CasePhoto.hash(
                    android.util.Base64.decode(
                        body.getString("image_base64"),
                        android.util.Base64.DEFAULT
                    )
                )
            )
            require(
                photo.captureContext == if (body.has("capture_context")) PhotoCaptureContext.parse(
                    body.getJSONObject("capture_context")
                ) else null
            )
            photo
        }

    fun canCreateOwnerCase() =
        checkedState().lastTokenResponse?.scope?.split(" ")?.contains("events:create") == true

    fun registeredVehiclesForDisplay() = forDisplay {
        val state = checkedState()
        val data = org.json.JSONObject(
            AuthenticatedTransport().myVehicles(
                BuildConfig.AUTH_API_URL,
                state.accessToken
            )
        )
        require(data.getString("schema") == "road-guardian.registered-vehicles.v1" && data.get("ownership_verified") == false)
        val values = data.getJSONArray("vehicles")
        require(values.length() <= 256)
        val ids = (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            val id = item.get("id")
            require(id is String && id.length in 1..128 && item.getString("identity_status") == "service_grant_only")
            id
        }
        require(ids.distinct().size == ids.size)
        ids
    }

    fun createOwnerCaseForDisplay(body: org.json.JSONObject, expectedSession: String) = forDisplay {
        if (sessionFingerprint() != expectedSession) throw AccountAccessException(
            AccountAccessException.Reason.CHANGED
        )
        val state = checkedState()
        if (state.lastTokenResponse?.scope?.split(" ")
                ?.contains("events:create") != true
        ) throw AccountAccessException(AccountAccessException.Reason.FORBIDDEN)
        OwnerCaseResponse.parse(
            AuthenticatedTransport().createOwnerCase(
                BuildConfig.AUTH_API_URL,
                body.toString(),
                state.accessToken
            ), body
        )
    }

    fun saveSdkObservationForDisplay(
        id: String,
        body: org.json.JSONObject,
        expectedSession: String
    ) = forDisplay {
        if (sessionFingerprint() != expectedSession) throw AccountAccessException(
            AccountAccessException.Reason.CHANGED
        )
        val state = checkedState()
        if (state.lastTokenResponse?.scope?.split(" ")
                ?.contains("events:observe") != true
        ) throw AccountAccessException(AccountAccessException.Reason.FORBIDDEN)
        SdkObservation.receipt(
            AuthenticatedTransport().saveSdkObservation(
                BuildConfig.AUTH_API_URL,
                id,
                body.toString(),
                state.accessToken
            ), body
        )
    }

    fun noteForDisplay(id: String) = forDisplay {
        check(BuildConfig.REVIEW_NOTES_ENABLED)
        if (!capabilities().notesRead) throw AccountAccessException(AccountAccessException.Reason.NOT_SUPPORTED)
        val state = checkedState()
        PrivateReviewNote.parse(
            AuthenticatedTransport().reviewNote(
                BuildConfig.AUTH_API_URL,
                id,
                state.accessToken
            ), id
        )
    }

    fun saveNoteForDisplay(
        id: String,
        revision: Int,
        text: String,
        requestId: String,
        expectedSession: String? = null
    ) = forDisplay {
        if (expectedSession != null && sessionFingerprint() != expectedSession) throw AccountAccessException(
            AccountAccessException.Reason.CHANGED
        )
        if (!canReviewNotes()) throw AccountAccessException(AccountAccessException.Reason.FORBIDDEN)
        if (!capabilities().notesWrite) throw AccountAccessException(AccountAccessException.Reason.FORBIDDEN)
        require(
            revision >= 0 && text.length <= 2000 && java.util.UUID.fromString(requestId)
                .toString() == requestId
        )
        val state = checkedState()
        val body = org.json.JSONObject().put("expected_revision", revision).put("note", text)
            .put("request_id", requestId)
        PrivateReviewNote.parse(
            AuthenticatedTransport().saveReviewNote(
                BuildConfig.AUTH_API_URL,
                id,
                body.toString(),
                state.accessToken
            ), id
        )
    }

    fun videoForDisplay(id: String): DisplayResult<Video> {
        var downloaded: Video? = null
        try {
            return forDisplay { video(id).also { downloaded = it } }
        } catch (failure: Exception) {
            downloaded?.file?.delete()
            throw failure
        }
    }

    private fun <T> forDisplay(read: () -> T): DisplayResult<T> {
        val state = checkedState()
        val snapshot = state.jsonSerializeString()
        val lease = AccountDisplayLease(
            state.accessTokenExpirationTime ?: 0L,
            System.currentTimeMillis(),
            android.os.SystemClock.elapsedRealtime()
        )
        val value = read()
        if (checkedState().jsonSerializeString() != snapshot) throw AccountAccessException(
            AccountAccessException.Reason.CHANGED
        )
        if (!lease.valid(
                System.currentTimeMillis(),
                android.os.SystemClock.elapsedRealtime()
            )
        ) throw AccountAccessException(AccountAccessException.Reason.EXPIRED)
        return DisplayResult(value, lease, fingerprint(snapshot))
    }

    private val store = LoginStateStore(context.applicationContext)
    private fun checkedState(): AuthState {
        try {
            LoginConfiguration.validate(BuildConfig.OIDC_ISSUER, BuildConfig.OIDC_CLIENT_ID)
            BackendEndpoint.resolve(BuildConfig.AUTH_API_URL, "/api/events", false)
        } catch (_: IllegalArgumentException) {
            throw AccountAccessException(AccountAccessException.Reason.NOT_CONFIGURED)
        } catch (_: IllegalStateException) {
            throw AccountAccessException(AccountAccessException.Reason.NOT_CONFIGURED)
        }
        val state = try {
            store.read()
        } catch (_: Exception) {
            throw AccountAccessException(AccountAccessException.Reason.SIGN_IN)
        }
        val request = state.lastAuthorizationResponse?.request
        if (!state.isAuthorized || request?.clientId != BuildConfig.OIDC_CLIENT_ID ||
            request.configuration.discoveryDoc?.issuer?.toString() != BuildConfig.OIDC_ISSUER ||
            request.redirectUri.toString() != LoginConfiguration.REDIRECT
        )
            throw AccountAccessException(AccountAccessException.Reason.SIGN_IN)
        if ((state.accessTokenExpirationTime ?: 0L) <= System.currentTimeMillis() + 30000)
            throw AccountAccessException(AccountAccessException.Reason.EXPIRED)
        if (!state.lastTokenResponse?.tokenType.equals("Bearer", ignoreCase = true))
            throw AccountAccessException(AccountAccessException.Reason.SIGN_IN)
        return state
    }

    fun events(): JSONArray = try {
        JSONArray(read(null))
    } catch (_: org.json.JSONException) {
        throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
    }

    fun page(cursor: String?, category: String = "all"): AccountPageResponse.Page {
        val state = checkedState()
        val snapshot = state.jsonSerializeString()
        val response = AuthenticatedTransport().casePage(
            BuildConfig.AUTH_API_URL,
            category,
            cursor,
            state.accessToken
        )
        if (checkedState().jsonSerializeString() != snapshot) throw AccountAccessException(
            AccountAccessException.Reason.CHANGED
        )
        return AccountPageResponse.parse(response, cursor, category)
    }

    fun event(id: String): org.json.JSONObject {
        val event = try {
            org.json.JSONObject(read(id))
        } catch (_: org.json.JSONException) {
            throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
        }
        if (event.optString("id") != id || !event.optBoolean("evidence_valid"))
            throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
        return event
    }

    fun video(id: String): Video {
        if (!application.privateVideoCacheReady) throw AccountAccessException(AccountAccessException.Reason.UNAVAILABLE)
        val state = checkedState()
        val snapshot = state.jsonSerializeString()
        val event = event(id)
        val metadata = event.optJSONObject("video") ?: throw AccountAccessException(
            AccountAccessException.Reason.NOT_FOUND
        )
        if (!metadata.optBoolean("integrity_valid") || metadata.optString("mime") != "video/mp4")
            throw AccountAccessException(AccountAccessException.Reason.RESPONSE)
        if (checkedState().jsonSerializeString() != snapshot) throw AccountAccessException(
            AccountAccessException.Reason.CHANGED
        )
        val file = java.io.File.createTempFile("account-video-", ".mp4", cache)
        try {
            file.outputStream().use { output ->
                AuthenticatedVideoTransport().download(
                    BuildConfig.AUTH_API_URL, id, state.accessToken,
                    metadata.optString("sha256"), metadata.optLong("bytes", -1), output
                )
            }
            if (checkedState().jsonSerializeString() != snapshot) throw AccountAccessException(
                AccountAccessException.Reason.CHANGED
            )
            return Video(
                file,
                event.optString("source") == "synthetic" || metadata.optString("source")
                    .startsWith("synthetic")
            )
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    fun measurementPackage(
        id: String,
        caseHash: String,
        vehicle: String,
        before: MeasurementProtocol.Summary,
        after: MeasurementProtocol.Summary
    ): java.io.File {
        if (!BuildConfig.MEASUREMENTS_ENABLED || !application.privateVideoCacheReady) throw AccountAccessException(
            AccountAccessException.Reason.NOT_SUPPORTED
        )
        val state = checkedState()
        val snapshot = state.jsonSerializeString()
        val file = java.io.File.createTempFile("measurement-export-", ".zip", cache)
        try {
            file.outputStream().use {
                AuthenticatedExportTransport().downloadMeasurement(
                    BuildConfig.AUTH_API_URL,
                    id,
                    before.id,
                    after.id,
                    state.accessToken,
                    it
                )
            }
            val verified =
                MeasurementPackageVerifier.verify(file, id, caseHash, vehicle, before.id, after.id)
            require(verified.before.summary.hash == before.hash && verified.after.summary.hash == after.hash)
            if (checkedState().jsonSerializeString() != snapshot) throw AccountAccessException(
                AccountAccessException.Reason.CHANGED
            )
            return file
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    fun evidencePackage(id: String): java.io.File {
        if (!application.privateVideoCacheReady) throw AccountAccessException(AccountAccessException.Reason.UNAVAILABLE)
        val state = checkedState()
        val snapshot = state.jsonSerializeString()
        val file = java.io.File.createTempFile("account-export-", ".zip", cache)
        try {
            file.outputStream().use { output ->
                AuthenticatedExportTransport().download(
                    BuildConfig.AUTH_API_URL,
                    id,
                    state.accessToken,
                    output
                )
            }
            EvidencePackageVerifier.verify(file, id)
            if (checkedState().jsonSerializeString() != snapshot) throw AccountAccessException(
                AccountAccessException.Reason.CHANGED
            )
            return file
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    private fun read(id: String?): String {
        val state = checkedState()
        val snapshot = state.jsonSerializeString()
        val transport = AuthenticatedTransport()
        val response = if (id == null) transport.events(BuildConfig.AUTH_API_URL, state.accessToken)
        else transport.event(BuildConfig.AUTH_API_URL, id, state.accessToken)
        if (checkedState().jsonSerializeString() != snapshot) throw AccountAccessException(
            AccountAccessException.Reason.CHANGED
        )
        return response
    }
}
