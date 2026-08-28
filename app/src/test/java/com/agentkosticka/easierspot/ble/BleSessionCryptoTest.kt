package com.agentkosticka.easierspot.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class BleSessionCryptoTest {
    @Test
    fun `authenticated peers derive the same key and decrypt credentials`() {
        val server = keyPair()
        val client = keyPair()
        val hello = BleSessionCrypto.createServerHello(server)
        val parsedHello = BleSessionCrypto.parseServerHello(hello.encoded)
        val auth = BleSessionCrypto.createClientAuth(client, parsedHello)
        val parsedAuth = BleSessionCrypto.parseAndVerifyClientAuth(auth, hello)

        val clientKey = BleSessionCrypto.sessionKey(client.private, parsedHello.publicKey, hello.nonce)
        val serverKey = BleSessionCrypto.sessionKey(server.private, parsedAuth.publicKey, hello.nonce)
        assertArrayEquals(clientKey.encoded, serverKey.encoded)
        assertEquals(
            BleSessionCrypto.pairingCode(clientKey, hello.nonce),
            BleSessionCrypto.pairingCode(serverKey, hello.nonce)
        )

        val plaintext = "test-ssid\u0000test-passphrase".toByteArray()
        val envelope = BleSessionCrypto.encrypt(serverKey, plaintext, hello.nonce)
        assertArrayEquals(plaintext, BleSessionCrypto.decrypt(clientKey, envelope, hello.nonce))
    }

    @Test
    fun `tampered client authentication is rejected`() {
        val server = keyPair()
        val client = keyPair()
        val hello = BleSessionCrypto.createServerHello(server)
        val auth = BleSessionCrypto.createClientAuth(client, hello)
        auth[auth.lastIndex] = (auth.last().toInt() xor 0x01).toByte()

        assertThrows(IllegalArgumentException::class.java) {
            BleSessionCrypto.parseAndVerifyClientAuth(auth, hello)
        }
    }

    @Test
    fun `tampered credential ciphertext is rejected`() {
        val server = keyPair()
        val client = keyPair()
        val hello = BleSessionCrypto.createServerHello(server)
        val key = BleSessionCrypto.sessionKey(server.private, client.public, hello.nonce)
        val envelope = BleSessionCrypto.encrypt(key, "secret".toByteArray(), hello.nonce)
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            BleSessionCrypto.decrypt(key, envelope, hello.nonce)
        }
    }

    @Test
    fun `pairing code changes for a different session nonce`() {
        val server = keyPair()
        val client = keyPair()
        val helloOne = BleSessionCrypto.createServerHello(server)
        val helloTwo = BleSessionCrypto.createServerHello(server)
        val keyOne = BleSessionCrypto.sessionKey(server.private, client.public, helloOne.nonce)
        val keyTwo = BleSessionCrypto.sessionKey(server.private, client.public, helloTwo.nonce)

        assertNotEquals(
            BleSessionCrypto.pairingCode(keyOne, helloOne.nonce),
            BleSessionCrypto.pairingCode(keyTwo, helloTwo.nonce)
        )
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
}
