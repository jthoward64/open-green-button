import com.google.cloud.tools.jib.gradle.JibTask
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlin.power.assert)
  alias(libs.plugins.testBalloon)
  alias(libs.plugins.jib)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.detekt)
  application
}

// Jib still calls Task.project at execution time, which the configuration cache forbids.
// Opt jib tasks out (everything else keeps the cache). https://github.com/GoogleContainerTools/jib/issues/3132
tasks.withType<JibTask>().configureEach {
  notCompatibleWithConfigurationCache("Jib is not compatible with the configuration cache")
}

detekt {
  buildUponDefaultConfig = true
  allRules = false
  config.setFrom(rootProject.file("../config/detekt.yml"))
}

tasks.withType<Detekt>().configureEach { jvmTarget = "21" }
tasks.withType<DetektCreateBaselineTask>().configureEach { jvmTarget = "21" }

// Don't lint generated code — testBalloon emits a JvmEntryPoint.kt under build/generated/ that
// uses its own indent convention, which conflicts with our indent_size=2 .editorconfig.
ktlint {
  filter {
    exclude { it.file.path.contains("/build/generated/") }
  }
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    freeCompilerArgs.addAll("-Xjsr305=strict")
  }
}

application {
  mainClass.set("org.opengb.AppKt")
}

powerAssert {
  functions = listOf("kotlin.assert")
  includedSourceSets = listOf("test")
}

dependencies {
  // Bootable bootstrap
  implementation(libs.bootable.boot)
  implementation(libs.bootable.config.common)
  implementation(libs.bootable.config.hoplite)
  implementation(libs.bootable.log4j2)

  // Kotlin idioms for log4j2 (KotlinLogger, withLoggingContext, StructuredMessage helpers)
  implementation(libs.log4j.api.kotlin)

  // Ktor server
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.status.pages)
  implementation(libs.ktor.server.call.logging)
  implementation(libs.ktor.server.call.id)
  implementation(libs.ktor.server.default.headers)
  implementation(libs.ktor.server.html.builder)
  implementation(libs.ktor.server.metrics.micrometer)
  implementation(libs.ktor.serialization.kotlinx.json)

  // Ktor client (for utility upstream calls)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.client.logging)

  // Config, caching, observability
  implementation(libs.hoplite.core)
  implementation(libs.hoplite.hocon)
  implementation(libs.caffeine)
  implementation(libs.micrometer.prometheus)

  // Coroutines + serialization + datetime
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.datetime)

  // log4j2 runtime
  runtimeOnly(libs.log4j.api)
  runtimeOnly(libs.log4j.core)
  runtimeOnly(libs.log4j.slf4j.impl)
  runtimeOnly(libs.log4j.layout.template.json)

  testImplementation(libs.testBalloon.framework.core)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.ktor.client.mock)

  // Add ktlint's rules to detekt. The wrapped rules read settings (indentSize, max line
  // length, etc.) from .editorconfig like standalone ktlint does — detekt.yml `formatting:`
  // block can further override per-rule.
  detektPlugins(libs.detekt.formatting)
}

tasks.test {
  useJUnitPlatform()
}

// Bundle the brand assets (favicons, app icons, manifest, logo) into the runtime classpath
// under `static/` so Ktor's staticResources can serve them. Sourced from branding/ rather than
// copied into the resources tree to keep one canonical home for the brand files.
tasks.processResources {
  from(rootProject.file("../branding/web")) {
    into("static")
  }
  from(rootProject.file("../branding/logo-horizontal.svg")) {
    into("static")
  }
  from(rootProject.file("../branding/icon.svg")) {
    into("static")
  }
}

jib {
  from {
    image = "eclipse-temurin:21-jre-jammy"
    platforms {
      platform {
        architecture = "amd64"
        os = "linux"
      }
    }
  }
  to {
    image =
      providers.gradleProperty("opengb.image.name")
        .orElse("ghcr.io/rocketraman/open-green-button-server")
        .get()
    tags = setOf("latest", project.version.toString())

    // When FLY_API_TOKEN is set (locally or in CI), authenticate directly with that token
    // — bypassing whatever stale credentials might be in ~/.docker/config.json or
    // /run/user/$UID/containers/auth.json. Fly's registry accepts any non-empty username
    // with the API token as the password. Falls back to ambient Docker credentials when
    // the env var is absent (e.g. pushing to GHCR with `docker login` creds).
    providers.environmentVariable("FLY_API_TOKEN").orNull?.let { token ->
      auth {
        username = "x"
        password = token
      }
    }
  }
  container {
    mainClass = "org.opengb.AppKt"
    ports = listOf("8080")
    jvmFlags =
      listOf(
        "-XX:+UseSerialGC",
        // Container-aware heap sizing. JVM 17+ reads the cgroup memory limit and computes
        // heap as a percentage of it — we get 75% (≈ 384 MB on a 512 MB Fly machine), leaving
        // the rest for metaspace, code cache, native, GC bookkeeping, and JNI buffers. Bump
        // the Fly machine memory via `fly scale memory 512 -a open-green-button` to take
        // effect; no rebuild needed when scaling up later.
        "-XX:MaxRAMPercentage=75.0",
        "-XX:TieredStopAtLevel=1",
        "-Dfile.encoding=UTF-8",
      )
    environment =
      mapOf(
        "OPENGB_PORT" to "8080",
      )
    creationTime.set("USE_CURRENT_TIMESTAMP")
  }
}
