package com.agentkosticka.easierspot.ble

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Cryptographic primitives and bounded binary envelopes for BLE protocol v2. */
object BleSessionCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val SERVER_ALIAS = "easierspot_v2_server_identity"
    private const val CLIENT_ALIAS = "easierspot_v2_client_identity"
    private const val NONCE_SIZE = 16
    private const val IV_SIZE = 12

    data class ServerHello(val nonce: ByteArray, val publicKey: PublicKey, val encoded: ByteArray)
    data class ClientAuth(val publicKey: PublicKey, val signature: ByteArray)

    fun serverKeyPair(context: Context): KeyPair = identityKeyPair(context, SERVER_ALIAS)
    fun clientKeyPair(context: Context): KeyPair = identityKeyPair(context, CLIENT_ALIAS)

    fun createServerHello(keyPair: KeyPair): ServerHello {
        val nonce = ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes)
        val publicBytes = keyPair.public.encoded
        val encoded = ByteBuffer.allocate(1 + NONCE_SIZE + 2 + publicBytes.size)
            .put(BleConstants.PROTOCOL_VERSION)
            .put(nonce)
            .putShort(publicBytes.size.toShort())
            .put(publicBytes)
            .array()
        return ServerHello(nonce, keyPair.public, encoded)
    }

    fun parseServerHello(value: ByteArray): ServerHello {
        require(value.size >= 1 + NONCE_SIZE + 2) { "Server hello is truncated" }
        val buffer = ByteBuffer.wrap(value)
        require(buffer.get() == BleConstants.PROTOCOL_VERSION) { "Unsupported protocol version" }
        val nonce = ByteArray(NONCE_SIZE).also(buffer::get)
        val keyLength = buffer.short.toInt() and 0xFFFF
        require(keyLength in 64..256 && buffer.remaining() == keyLength) { "Invalid server key" }
        val publicBytes = ByteArray(keyLength).also(buffer::get)
        return ServerHello(nonce, decodePublicKey(publicBytes), value.copyOf())
    }

    fun createClientAuth(clientKeyPair: KeyPair, hello: ServerHello): ByteArray {
        val clientPublic = clientKeyPair.public.encoded
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(clientKeyPair.private)
            update(transcript(hello, clientPublic))
            sign()
        }
        return ByteBuffer.allocate(1 + 2 + clientPublic.size + 2 + signature.size)
            .put(BleConstants.PROTOCOL_VERSION)
            .putShort(clientPublic.size.toShort())
            .put(clientPublic)
            .putShort(signature.size.toShort())
            .put(signature)
            .array()
    }

    fun parseAndVerifyClientAuth(value: ByteArray, hello: ServerHello): ClientAuth {
        require(value.size >= 5) { "Client authentication is truncated" }
        val buffer = ByteBuffer.wrap(value)
        require(buffer.get() == BleConstants.PROTOCOL_VERSION) { "Unsupported protocol version" }
        val keyLength = buffer.short.toInt() and 0xFFFF
        require(keyLength in 64..256 && buffer.remaining() >= keyLength + 2) { "Invalid client key" }
        val publicBytes = ByteArray(keyLength).also(buffer::get)
        val signatureLength = buffer.short.toInt() and 0xFFFF
        require(signatureLength in 48..128 && buffer.remaining() == signatureLength) { "Invalid signature" }
        val signatureBytes = ByteArray(signatureLength).also(buffer::get)
        val publicKey = decodePublicKey(publicBytes)
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(transcript(hello, publicBytes))
            verify(signatureBytes)
        }
        require(verified) { "Client authentication failed" }
        return ClientAuth(publicKey, signatureBytes)
    }

    fun sessionKey(privateKey: PrivateKey, peerPublicKey: PublicKey, nonce: ByteArray): SecretKeySpec {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peerPublicKey, true)
            generateSecret()
        }
        val prk = hmac(nonce, shared)
        val key = hmac(prk, "EasierSpot BLE v2\u0001".toByteArray()).copyOf(32)
        shared.fill(0)
        prk.fill(0)
        return SecretKeySpec(key, "AES")
    }

    fun encrypt(key: SecretKeySpec, plaintext: ByteArray, nonce: ByteArray): ByteArray {
        val iv = ByteArray(IV_SIZE).also(SecureRandom()::nextBytes)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(nonce)
            doFinal(plaintext)
        }
        return byteArrayOf(BleConstants.PROTOCOL_VERSION) + iv + ciphertext
    }

    fun decrypt(key: SecretKeySpec, envelope: ByteArray, nonce: ByteArray): ByteArray {
        require(envelope.size > 1 + IV_SIZE + 16 && envelope[0] == BleConstants.PROTOCOL_VERSION) {
            "Encrypted credential envelope is invalid"
        }
        val iv = envelope.copyOfRange(1, 1 + IV_SIZE)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(nonce)
            doFinal(envelope, 1 + IV_SIZE, envelope.size - 1 - IV_SIZE)
        }
    }

    fun fingerprint(publicKey: PublicKey): String = MessageDigest.getInstance("SHA-256")
        .digest(publicKey.encoded)
        .take(8)
        .joinToString("") { "%02x".format(it) }

    fun pairingCode(key: SecretKeySpec, nonce: ByteArray): String {
        val value = hmac(key.encoded, nonce)
        val number = ByteBuffer.wrap(value.copyOf(4)).int.toLong().and(0xFFFF_FFFFL) % 1_000_000L
        value.fill(0)
        return number.toString().padStart(6, '0')
    }

    private fun transcript(hello: ServerHello, clientPublic: ByteArray): ByteArray =
        hello.nonce + hello.publicKey.encoded + clientPublic

    private fun decodePublicKey(encoded: ByteArray): PublicKey = KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(encoded))

    private fun identityKeyPair(context: Context, alias: String): KeyPair {
        context.applicationContext // Ensure callers do not accidentally retain an Activity.
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return KeyPair(existing.certificate.publicKey, existing.privateKey)

        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
            initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or
                        KeyProperties.PURPOSE_AGREE_KEY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKeyPair()
        }
    }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }
}
