package ai.teraq.roadguardian

import android.content.Context
import android.net.Uri
import net.openid.appauth.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Synthetic state in the separate integration package; never an identity-provider login. */
object IntegrationIdentity {
    fun install(context: Context, tokenKey: String = "token"): JSONObject {
        require(
            tokenKey in setOf(
                "token",
                "read_only_token",
                "other_owner_token",
                "no_vehicle_token",
                "observation_token",
                "owner_case_token",
                "photo_token",
                "powertrain_token",
                "measurement_token",
                "measurement_photo_token"
            )
        )
        check(context.packageName == "ai.teraq.roadguardian.integration" && !BuildConfig.SYNTHETIC_BACKEND)
        check(BuildConfig.AUTH_API_URL == "https://localhost:9443")
        val fixture = try {
            JSONObject(File(context.filesDir, "android-fixture.json").readText())
        } catch (_: Exception) {
            throw IllegalStateException("합성 시험 계정 파일을 확인하지 못했습니다")
        }
        val issuer = BuildConfig.OIDC_ISSUER
        val scope = when (tokenKey) {
            "measurement_photo_token" -> "openid events:read events:measure events:photos"
            "measurement_token" -> "openid events:read events:measure"
            "powertrain_token" -> "openid events:read events:powertrain"
            "photo_token" -> "openid events:read events:create events:photos"
            "read_only_token" -> "openid events:read"
            "observation_token" -> "openid events:read events:observe"
            "owner_case_token" -> "openid events:read events:create events:observe events:review"
            else -> fixture.optString("scope", "openid events:read")
        }
        val discovery =
            JSONObject().put("issuer", issuer).put("authorization_endpoint", "$issuer/authorize")
                .put("token_endpoint", "$issuer/token").put("jwks_uri", "$issuer/keys")
                .put("response_types_supported", JSONArray().put("code"))
                .put("subject_types_supported", JSONArray().put("public"))
                .put("id_token_signing_alg_values_supported", JSONArray().put("RS256"))
        val configuration =
            AuthorizationServiceConfiguration(AuthorizationServiceDiscovery(discovery))
        val request = AuthorizationRequest.Builder(
            configuration,
            BuildConfig.OIDC_CLIENT_ID,
            "code",
            Uri.parse(LoginConfiguration.REDIRECT)
        )
            .setScope(scope).build()
        val authorization =
            AuthorizationResponse.Builder(request).setAuthorizationCode("synthetic-code")
                .setState(request.state).build()
        val token =
            TokenResponse.Builder(authorization.createTokenExchangeRequest()).setTokenType("Bearer")
                .setScope(scope)
                .setAccessToken(fixture.getString(tokenKey))
                .setAccessTokenExpirationTime(fixture.getLong("expires_at")).build()
        LoginStateStore(context).write(AuthState(authorization, token, null))
        return fixture
    }
}
