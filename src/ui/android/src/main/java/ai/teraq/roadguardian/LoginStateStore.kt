package ai.teraq.roadguardian

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import net.openid.appauth.AuthState
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 토큰은 백업에서 빠지는 암호화 파일에, 키는 Android Keystore에 둔다 */
class LoginStateStore(context: Context) {
    private val notifications =
        context.getSystemService(android.app.NotificationManager::class.java)
    private val file = AtomicFile(File(context.noBackupFilesDir, "login-state.bin"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    fun read(): AuthState = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized AuthState()
        val data = file.openRead().use { it.readBytes() }
        require(data.size in 29..1048576)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(ALIAS.toByteArray())
        AuthState.jsonDeserialize(
            String(
                cipher.doFinal(data.copyOfRange(12, data.size)),
                Charsets.UTF_8
            )
        )
    }

    fun write(state: AuthState) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(ALIAS.toByteArray())
        val encrypted =
            cipher.iv + cipher.doFinal(state.jsonSerializeString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        // 계정을 바꾸면 이전 세션의 일반 알림도 지운다
        try {
            notifications?.activeNotifications?.filter { it.tag?.startsWith("account-event:") == true }
                ?.forEach { notifications.cancel(it.tag, it.id) }
        } catch (_: Exception) {/* Saved account state still controls access when notification removal fails. */
        }
    }

    internal fun ifCurrent(expected: String, action: () -> Unit): Boolean = synchronized(lock) {
        if (read().jsonSerializeString() != expected) return@synchronized false
        action()
        true
    }

    companion object {
        private val lock = Any()
        private const val ALIAS = "road-guardian-login-v1"
    }
}
