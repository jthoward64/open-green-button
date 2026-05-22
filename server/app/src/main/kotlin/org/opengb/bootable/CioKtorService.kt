package org.opengb.bootable

import com.github.rocketraman.bootable.boot.AdvancedAppService
import com.github.rocketraman.bootable.config.common.HostPort
import com.github.rocketraman.bootable.config.common.host
import com.github.rocketraman.bootable.config.common.port
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory

/**
 * A Bootable [AdvancedAppService] that runs a Ktor server using the CIO engine.
 *
 * Bootable's built-in `KtorService` (in `boot-server-http-ktor`) hardcodes the Netty engine via
 * a `lateinit var server: EmbeddedServer<NettyApplicationEngine, ...>`, with no override hook.
 * We use CIO instead — it's pure Kotlin, lighter on cold start, and the only Ktor engine that
 * works with GraalVM native-image (post-MVP optimization in Phase 6 of the roadmap).
 *
 * Subclasses provide [Application.module] to install routes and plugins.
 */
abstract class CioKtorService(
    private val name: String,
    private val hostPort: HostPort,
) : AdvancedAppService {
    private lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private lateinit var die: () -> Unit

    override fun name(): String = name

    /** Defaults to the lowest priority so the HTTP listener is the last thing to start, first to stop. */
    override fun priority(): Int = Int.MIN_VALUE

    override fun start(die: () -> Unit) {
        this.die = die
        val environment =
            applicationEnvironment {
                log = LoggerFactory.getLogger(name)
            }
        server =
            embeddedServer(
                CIO,
                environment = environment,
                configure = {
                    connector {
                        host = this@CioKtorService.hostPort.host()
                        port = this@CioKtorService.hostPort.port(DEFAULT_PORT)
                    }
                },
                module = { module() },
            )
        server.start(wait = false)
    }

    override fun shutdown() {
        if (::server.isInitialized) {
            server.stop(GRACE_PERIOD_MS, FORCED_STOP_TIMEOUT_MS)
        }
    }

    abstract fun Application.module()

    companion object {
        private const val DEFAULT_PORT = 8080
        private const val GRACE_PERIOD_MS = 50L
        private const val FORCED_STOP_TIMEOUT_MS = 1_000L
    }
}
