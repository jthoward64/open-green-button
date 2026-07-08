package org.opengb.routes

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel

/**
 * Zero-copy streaming response body: hands the server engine the upstream [ByteReadChannel] to read
 * from directly (a **pull-based** [OutgoingContent.ReadChannelContent]).
 *
 * This is what lets the proxy stream a large ESPI feed without materialising it. The obvious
 * `respondBytesWriter { upstream.copyTo(this) }` is a **push** producer that the engine runs during
 * the body-writing phase — which happens *after* the route handler (and any `client.execute { }`
 * block) returns, so the upstream response is already cancelled → `ClosedByteChannelException`. Using
 * the no-block `client.execute()` avoids that but reads the whole body into memory (OOM on big feeds).
 * A `ReadChannelContent` is pulled by the engine *as part of* `respond(...)`, so the copy completes
 * before `respond()` returns — the upstream stays alive inside the `execute { }` block, no buffering.
 */
internal class ByteReadChannelContent(
  private val channel: ByteReadChannel,
  override val contentType: ContentType,
  override val contentLength: Long? = null,
) : OutgoingContent.ReadChannelContent() {
  override fun readFrom(): ByteReadChannel = channel
}
