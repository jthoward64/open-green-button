package org.opengb.observability

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import org.apache.logging.log4j.kotlin.logger
import org.apache.logging.log4j.kotlin.withLoggingContext

private val accessLog = logger("opengb.access")

/**
 * One structured log entry per request, emitted at the end of the pipeline.
 *
 * Field placement:
 *  - **Per-event fields** (method, path, status, duration, client IP, user agent) live in the
 *    [AccessLogMessage] MapMessage payload and reach the JSON output via the `map` resolver.
 *  - **Request-scoped fields** (`http.request.id`, `trace.id`) live in ThreadContext for the
 *    duration of the request via [withLoggingContext], so any *other* log statement emitted
 *    during the request (e.g. an OAuth token-exchange warning) inherits them automatically.
 *    They reach the JSON output via the `mdc` resolver.
 *
 * Probes (`/health`, `/ready`) and the Fly metrics scrape (`/metrics`) are skipped so they don't
 * fill the log stream with noise.
 */
fun Application.installAccessLog() {
  intercept(ApplicationCallPipeline.Setup) {
    val path = call.request.local.uri
    if (path == "/health" || path == "/ready" || path == "/metrics") {
      proceed()
      return@intercept
    }

    val start = System.nanoTime()
    val traceId = call.request.headers["Fly-Trace-Id"]
    val requestId = call.callId

    val contextMap: Map<String, String> =
      buildMap {
        requestId?.let { put("http.request.id", it) }
        traceId?.let { put("trace.id", it) }
      }

    // withLoggingContext is coroutine-aware — the ThreadContext entries are preserved
    // across suspension points inside proceed().
    withLoggingContext(contextMap) {
      try {
        proceed()
      } finally {
        AccessLogMessage(
          method = call.request.local.method.value,
          path = path,
          status = call.response.status()?.value ?: 0,
          durationNanos = System.nanoTime() - start,
          clientIp = call.request.headers["Fly-Client-IP"] ?: call.request.local.remoteHost,
          userAgent = call.request.headers["User-Agent"],
        ).log()
      }
    }
  }
}

/**
 * One structured log entry for an HTTP request. Field names follow ECS schema where possible so
 * downstream tooling (Grafana, OpenSearch with ECS templates, etc.) understands them out of the
 * box. `trace.id` and `http.request.id` are intentionally *not* fields on this class — they
 * come from ThreadContext set by [installAccessLog].
 */
class AccessLogMessage(
  val method: String,
  val path: String,
  val status: Int,
  val durationNanos: Long,
  val clientIp: String?,
  val userAgent: String?,
) : StructuredLogMessage() {
  init {
    field("http.request.method", method)
    field("url.path", path)
    field("http.response.status_code", status)
    field("event.duration", durationNanos)
    field("client.ip", clientIp)
    field("user_agent.original", userAgent)
  }

  override val humanMessage: String
    get() = "$method $path -> $status"

  fun log() = accessLog.info(this)
}
