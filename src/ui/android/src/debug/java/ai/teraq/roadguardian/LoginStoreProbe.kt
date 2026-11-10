package ai.teraq.roadguardian

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import android.net.Uri
import java.io.File
import net.openid.appauth.*

/** Synthetic token in an isolated directory; no account authentication or browser action. */
class LoginStoreProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val scratch = File(
                targetContext.cacheDir,
                "login-probe-${System.currentTimeMillis()}"
            ).apply { mkdirs() }
            val isolated = object : ContextWrapper(targetContext) {
                override fun getNoBackupFilesDir() = scratch
            }
            val config = AuthorizationServiceConfiguration(
                Uri.parse("https://identity.example/auth"),
                Uri.parse("https://identity.example/token")
            )
            val request =
                TokenRequest.Builder(config, "fixture-client").setGrantType("authorization_code")
                    .setAuthorizationCode("fixture-code")
                    .setRedirectUri(Uri.parse(LoginConfiguration.REDIRECT)).build()
            val token = "synthetic-token-not-a-credential"
            val response =
                TokenResponse.Builder(request).setAccessToken(token).setTokenType("Bearer")
                    .setAccessTokenExpirationTime(System.currentTimeMillis() + 60000).build()
            val state = AuthState(config).apply { update(response, null) }
            val store = LoginStateStore(isolated)
            store.write(state)
            val file = File(scratch, "login-state.bin")
            val encrypted = file.readBytes()
            check(!String(encrypted, Charsets.ISO_8859_1).contains(token))
            check(LoginStateStore(isolated).read().accessToken == token)
            File(scratch, "original-encrypted.bin").writeBytes(encrypted)
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            file.writeBytes(encrypted)
            check(runCatching { store.read() }.isFailure)
            store.write(AuthState())
            check(!store.read().isAuthorized)
            result.putString(
                "stream",
                "PASS: Android Keystore encrypted synthetic token, reopened without plaintext storage, tamper rejected, local sign-out clears authorized state. No real login tested.\n"
            )
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
