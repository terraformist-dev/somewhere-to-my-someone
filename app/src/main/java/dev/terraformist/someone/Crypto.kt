package dev.terraformist.someone

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The scrambling. When two phones pair, each makes a key pair and they swap the public halves
 * through Firebase. From those, both work out the same secret (ECDH), which never leaves the
 * phones. Locations are locked with it (AES-GCM) before they're sent, so Firebase only ever
 * holds gibberish. The same secret makes the rotating Bluetooth "it's me" signal.
 */
object Crypto {

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    /** A random id that's safe to use as a Firebase key. */
    fun randomId(): String =
        Base64.encodeToString(randomBytes(16), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    /** A random six-digit code, 000000..999999. */
    fun randomCode(): String = String.format(java.util.Locale.US, "%06d", random.nextInt(1_000_000))

    // ---------- Pairing ----------

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }.generateKeyPair()

    fun publicOf(pair: KeyPair): String = b64(pair.public.encoded)

    fun privateOf(pair: KeyPair): String = b64(pair.private.encoded)

    fun privateFrom(text: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(text)))

    /** The shared secret both phones arrive at, bound to this pairing's id. */
    fun sharedSecret(mine: PrivateKey, theirPublic: String, pairId: String): ByteArray {
        val theirs = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(theirPublic)))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(mine)
        agreement.doPhase(theirs, true)
        val raw = agreement.generateSecret()
        // HKDF (RFC 5869) with SHA-256: extract, then one block of expand.
        val prk = hmac(pairId.toByteArray(), raw)
        return hmac(prk, "somewhere-to-my-someone v1".toByteArray() + byteArrayOf(1))
    }

    // ---------- Locking and unlocking messages ----------

    private fun subKey(secret: ByteArray, label: String): ByteArray = hmac(secret, label.toByteArray())

    fun lock(secret: ByteArray, text: String): String {
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(subKey(secret, "location"), "AES"), GCMParameterSpec(128, iv))
        return b64(iv + cipher.doFinal(text.toByteArray()))
    }

    /** The original text, or null if it was tampered with or locked with a different key. */
    fun unlock(secret: ByteArray, locked: String): String? = try {
        val all = unb64(locked)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(subKey(secret, "location"), "AES"),
            GCMParameterSpec(128, all, 0, 12),
        )
        String(cipher.doFinal(all, 12, all.size - 12))
    } catch (e: Exception) {
        null
    }

    // ---------- Bluetooth ----------

    /** How often the Bluetooth signal changes, so strangers can't follow a phone around by it. */
    const val BLE_WINDOW_MS = 15 * 60_000L

    /** Eight bytes that say "it's [role] of this pair", changing every 15 minutes. */
    fun bleToken(secret: ByteArray, role: String, window: Long): ByteArray =
        hmac(subKey(secret, "bluetooth"), "$role|$window".toByteArray()).copyOf(8)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
