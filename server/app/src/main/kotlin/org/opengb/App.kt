package org.opengb

import com.github.rocketraman.bootable.boot.bindAppService
import com.github.rocketraman.bootable.boot.boot
import com.github.rocketraman.bootable.logging.log4j2.LoggingType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import kotlinx.serialization.json.Json
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.new
import org.kodein.di.singleton
import org.opengb.bootable.CioKtorService
import org.opengb.config.AppConfig
import org.opengb.config.loadConfig
import org.opengb.oauth.ClaimStore
import org.opengb.oauth.OAuthClient
import org.opengb.oauth.StateStore
import org.opengb.observability.installAccessLog
import org.opengb.proxy.TokenCrypto
import org.opengb.routes.installClaim
import org.opengb.routes.installConnect
import org.opengb.routes.installLanding
import org.opengb.routes.installLiveness
import org.opengb.utility.UtilityRegistry
import java.time.Duration
import java.util.UUID

/**
 * Bundle of constructed singletons shared across the request handlers. Built by [buildAppDeps]
 * (used by `boot { ... }` to populate the DI graph) and by tests with their own substitutes
 * (e.g. a Ktor MockEngine for the OAuth upstream client).
 */
data class AppDeps(
    val config: AppConfig,
    val crypto: TokenCrypto,
    val registry: UtilityRegistry,
    val stateStore: StateStore,
    val claimStore: ClaimStore,
    val oauth: OAuthClient,
)

fun buildAppDeps(
    config: AppConfig,
    http: HttpClient? = null,
): AppDeps {
    val crypto = TokenCrypto(config.crypto)
    val registry = UtilityRegistry(config.utilities)
    val stateStore = StateStore(ttl = Duration.ofSeconds(config.state.oauthStateTtlSeconds))
    val claimStore = ClaimStore(ttl = Duration.ofSeconds(config.state.claimCodeTtlSeconds))
    val httpClient = http ?: HttpClient(CIO)
    val oauth = OAuthClient(httpClient)
    return AppDeps(config, crypto, registry, stateStore, claimStore, oauth)
}

/**
 * The Bootable AppService that owns the HTTP listener for the proxy server. Constructor params
 * are resolved by Kodein-DI from the singletons bound in [opengbModule].
 */
class OpenGbServer(
    private val deps: AppDeps,
) : CioKtorService(name = "opengb", hostPort = deps.config.server) {
    override fun Application.module() {
        appModule(deps)
    }
}

/**
 * DI module wiring our app components together. Each component is a singleton constructed once
 * at boot. The HTTP client used to call utility token endpoints is also a singleton — it owns
 * a connection pool and should be reused across requests.
 */
val opengbModule =
    DI.Module("opengb") {
        bind<AppConfig> { singleton { loadConfig() } }
        bind<HttpClient> { singleton { HttpClient(CIO) } }
        bind<TokenCrypto> { singleton { TokenCrypto(instance<AppConfig>().crypto) } }
        bind<UtilityRegistry> { singleton { UtilityRegistry(instance<AppConfig>().utilities) } }
        bind<StateStore> {
            singleton {
                StateStore(ttl = Duration.ofSeconds(instance<AppConfig>().state.oauthStateTtlSeconds))
            }
        }
        bind<ClaimStore> {
            singleton {
                ClaimStore(ttl = Duration.ofSeconds(instance<AppConfig>().state.claimCodeTtlSeconds))
            }
        }
        bind<OAuthClient> { singleton { OAuthClient(instance()) } }
        bind<AppDeps> {
            singleton {
                AppDeps(
                    config = instance(),
                    crypto = instance(),
                    registry = instance(),
                    stateStore = instance(),
                    claimStore = instance(),
                    oauth = instance(),
                )
            }
        }
        bindAppService { singleton { new(::OpenGbServer) } }
    }

fun main() {
    boot(loggingType = LoggingType.JSON) {
        import(opengbModule)
    }
}

/**
 * Installs the request pipeline + all routes. Used by [OpenGbServer.module] in production and
 * by the test suite (which constructs an [AppDeps] directly with mocked HTTP).
 */
internal fun Application.appModule(deps: AppDeps) {
    install(DefaultHeaders) {
        header("X-Frame-Options", "DENY")
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "no-referrer")
    }
    install(CallId) {
        generate { UUID.randomUUID().toString() }
        retrieveFromHeader("X-Request-Id")
        replyToHeader("X-Request-Id")
    }
    // Structured per-request log via a log4j2 StructuredMessage subclass + coroutine-safe
    // ThreadContext propagation (so any non-access log emitted *during* the request also
    // inherits http.request.id and trace.id). See [installAccessLog] for details.
    installAccessLog()
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = false
                ignoreUnknownKeys = true
                encodeDefaults = false
            },
        )
    }
    installLiveness()
    installLanding(deps.config)
    installConnect(
        config = deps.config,
        registry = deps.registry,
        stateStore = deps.stateStore,
        claimStore = deps.claimStore,
        crypto = deps.crypto,
        oauth = deps.oauth,
    )
    installClaim(deps.claimStore)
}
