package org.opengb.proxy

import com.sksamuel.hoplite.Masked
import de.infix.testBalloon.framework.core.testSuite
import org.opengb.config.CryptoConfig
import java.util.Base64

val TokenCryptoTest by testSuite {
  val crypto = TokenCrypto(testCryptoConfig())

  test("encrypt then decrypt yields the original blob") {
    val original =
      RefreshBlob(
        utilityId = "burlington_hydro",
        refreshToken = "rt_secret_abcdef0123456789",
        subscriptionUri = "https://utility.example/Subscription/42",
        scope = "FB=1_3;IntervalDuration=900",
        issuedAt = 1_700_000_000,
      )
    val ciphertext = crypto.encrypt(original)
    val recovered = crypto.decrypt(ciphertext)
    assert(recovered == original)
  }

  test("ciphertext is non-deterministic across encryptions of the same blob") {
    val blob = sampleBlob()
    val a = crypto.encrypt(blob)
    val b = crypto.encrypt(blob)
    assert(a != b) { "GCM IV must be random per-encryption" }
  }

  test("decrypt rejects a tampered ciphertext") {
    val ciphertext = crypto.encrypt(sampleBlob())
    // Flip a bit deep in the body to corrupt the auth tag.
    val tampered =
      ciphertext.toCharArray().also { chars ->
        val i = chars.size - 4
        chars[i] = if (chars[i] == 'A') 'B' else 'A'
      }.concatToString()
    try {
      crypto.decrypt(tampered)
      throw AssertionError("decrypt should have raised on tampered ciphertext")
    } catch (_: BlobDecryptionException) {
      // expected
    }
  }

  test("decrypt rejects an unknown version byte") {
    val ciphertext = crypto.encrypt(sampleBlob())
    val raw = Base64.getUrlDecoder().decode(ciphertext)
    raw[0] = 0x99.toByte()
    val rewrapped = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    try {
      crypto.decrypt(rewrapped)
      throw AssertionError("decrypt should reject unknown version")
    } catch (e: BlobDecryptionException) {
      assert(e.message?.contains("version") == true)
    }
  }

  test("deriveProxyToken is deterministic for the same blob") {
    val blob = sampleBlob()
    val a = crypto.deriveProxyToken(blob)
    val b = crypto.deriveProxyToken(blob)
    assert(a == b)
  }

  test("deriveProxyToken changes when the refresh token changes") {
    val a = crypto.deriveProxyToken(sampleBlob(refreshToken = "rt_one"))
    val b = crypto.deriveProxyToken(sampleBlob(refreshToken = "rt_two"))
    assert(a != b)
  }

  test("deriveProxyToken changes when the utility id changes") {
    val a = crypto.deriveProxyToken(sampleBlob(utilityId = "u_one"))
    val b = crypto.deriveProxyToken(sampleBlob(utilityId = "u_two"))
    assert(a != b)
  }

  test("verifyProxyToken accepts the legitimate token and rejects others") {
    val blob = sampleBlob()
    val good = crypto.deriveProxyToken(blob)
    assert(crypto.verifyProxyToken(blob, good))
    assert(!crypto.verifyProxyToken(blob, good + "x"))
    assert(!crypto.verifyProxyToken(blob, ""))
  }

  test("AES key of wrong size is rejected") {
    val tooShort = Base64.getEncoder().encodeToString(ByteArray(16))
    try {
      TokenCrypto(
        CryptoConfig(
          aesKeyBase64 = Masked(tooShort),
          hmacPepperBase64 = Masked(Base64.getEncoder().encodeToString(ByteArray(32))),
        ),
      )
      throw AssertionError("16-byte AES key should be rejected")
    } catch (e: IllegalArgumentException) {
      assert(e.message?.contains("256 bits") == true)
    }
  }
}

private fun sampleBlob(
  utilityId: String = "burlington_hydro",
  refreshToken: String = "rt_test_abcdef",
): RefreshBlob =
  RefreshBlob(
    utilityId = utilityId,
    refreshToken = refreshToken,
    subscriptionUri = "https://utility.example/Subscription/1",
    scope = "FB=1",
  )

private fun testCryptoConfig(): CryptoConfig {
  val key32 = ByteArray(32) { (it + 1).toByte() }
  val pepper32 = ByteArray(32) { ((it + 1) * 7).toByte() }
  return CryptoConfig(
    aesKeyBase64 = Masked(Base64.getEncoder().encodeToString(key32)),
    hmacPepperBase64 = Masked(Base64.getEncoder().encodeToString(pepper32)),
  )
}
