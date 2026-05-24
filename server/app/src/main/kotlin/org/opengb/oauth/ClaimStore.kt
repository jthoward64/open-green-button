package org.opengb.oauth

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Duration

/**
 * Server-side payload held against a one-time claim code until the HA client redeems it.
 *
 * The encrypted refresh blob is the ciphertext output of [org.opengb.proxy.TokenCrypto.encrypt];
 * the proxy_token is the HMAC derivation. Holding these together as a single record lets the
 * `/claim/{code}` handler be a trivial atomic remove with no extra computation.
 */
@Serializable
data class ClaimRecord(
  val utilityId: String,
  val encryptedRefreshBlob: String,
  val proxyToken: String,
  val subscriptionUri: String? = null,
  val scope: String? = null,
)

class ClaimStore(
  ttl: Duration,
  maxEntries: Long = 10_000,
  private val random: SecureRandom = SecureRandom(),
) {
  private val cache: Cache<String, ClaimRecord> =
    Caffeine.newBuilder()
      .expireAfterWrite(ttl)
      .maximumSize(maxEntries)
      .build()

  fun create(record: ClaimRecord): String {
    val code = generateCode()
    cache.put(code, record)
    return code
  }

  /** Atomically consumes the claim code. Returns null if the code is unknown or already used. */
  fun redeem(code: String): ClaimRecord? = cache.asMap().remove(code)

  fun size(): Long = cache.estimatedSize()

  private fun generateCode(): String {
    // ≈110 bits of entropy in a human-friendly form.
    val bytes = ByteArray(14)
    random.nextBytes(bytes)
    val sb = StringBuilder("gb_live_")
    for (b in bytes) {
      sb.append(ALPHABET[(b.toInt() ushr 4) and 0x0F])
      sb.append(ALPHABET[b.toInt() and 0x0F])
    }
    return sb.toString().take(CODE_LENGTH)
  }

  companion object {
    /** Base32-ish alphabet without confusing characters (0/O, 1/l/I). */
    private val ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz".toCharArray()
    private const val CODE_LENGTH = "gb_live_".length + 22
  }
}
