package com.androidmcp.hub.relay

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Non-exportable device signing identity for the Intentions Relay.
 *
 * The private key is generated inside AndroidKeyStore and is used only for SHA-256 ECDSA signing.
 * It is intentionally separate from APK signing, the localhost bearer token, and H2 confirmation
 * state keys so compromise/revocation domains remain independent.
 */
object RelayDeviceIdentity {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "llm_intentions_relay_device_v1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    @Synchronized
    private fun ensureIdentity() {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(ALIAS)) return

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    fun publicKeySpkiB64(): String {
        ensureIdentity()
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val certificate = checkNotNull(keyStore.getCertificate(ALIAS)) {
            "Relay device certificate missing after key generation"
        }
        return encoder.encodeToString(certificate.publicKey.encoded)
    }

    fun keyId(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(Base64.getUrlDecoder().decode(publicKeySpkiB64()))
        return encoder.encodeToString(digest)
    }

    fun signDerB64(message: ByteArray): String {
        ensureIdentity()
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val privateKey = checkNotNull(keyStore.getKey(ALIAS, null)) {
            "Relay device private key missing after key generation"
        }
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey as java.security.PrivateKey)
        signature.update(message)
        return encoder.encodeToString(signature.sign())
    }

    fun randomNonceB64(bytes: Int = 32): String {
        require(bytes in 16..64) { "nonce length must be between 16 and 64 bytes" }
        val nonce = ByteArray(bytes)
        SecureRandom().nextBytes(nonce)
        return encoder.encodeToString(nonce)
    }

    fun enrollmentProofMessage(
        token: String,
        publicKeySpkiB64: String,
        clientNonceB64: String,
    ): ByteArray = (
        "llm-intentions-enroll-v1\n" +
            token + "\n" +
            publicKeySpkiB64 + "\n" +
            clientNonceB64
        ).toByteArray(Charsets.UTF_8)

    fun authProofMessage(challenge: RelayAuthChallenge): ByteArray = (
        "llm-intentions-relay-auth-v1\n" +
            challenge.deviceId + "\n" +
            challenge.sessionId + "\n" +
            challenge.nonceB64 + "\n" +
            challenge.expiresAtMs
        ).toByteArray(Charsets.UTF_8)
}
