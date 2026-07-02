import com.google.cloud.tools.jib.gradle.JibTask
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlin.power.assert)
  alias(libs.plugins.testBalloon)
  alias(libs.plugins.jib)
  alias(libs.plugins.graalvm.native)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.detekt)
  application
}

// Jib still calls Task.project at execution time, which the configuration cache forbids.
// Opt jib tasks out (everything else keeps the cache). https://github.com/GoogleContainerTools/jib/issues/3132
tasks.withType<JibTask>().configureEach {
  notCompatibleWithConfigurationCache("Jib is not compatible with the configuration cache")
}

// The GraalVM plugin's tasks (nativeCompile, generateResourcesConfigFile, …) resolve dependency
// configurations at execution time, which the configuration cache forbids (same class of issue
// as Jib above). Opt the whole plugin's task set out by package — the rest of the build still
// uses the cache. Native images are built as a one-off in Docker/CI, so this costs nothing.
tasks
  .matching { it.javaClass.name.startsWith("org.graalvm.buildtools.gradle.tasks.") }
  .configureEach {
    notCompatibleWithConfigurationCache("GraalVM native build tasks resolve configurations at execution time")
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

// GraalVM native image — produces a self-contained binary for the scale-to-zero Fly.io
// deployment (see ../../Dockerfile and docs/deployment.md). CIO is the only Ktor engine that
// works under native-image; see bootable/CioKtorService.kt for why we committed to it. The
// binary is compiled inside the GraalVM container by the Dockerfile, so a local GraalVM is only
// needed when running `nativeCompile`/the tracing agent directly.
graalvmNative {
  // Pull the community reachability metadata for libraries we depend on (kotlinx-*, ktor,
  // log4j2, caffeine, …) so we don't hand-maintain reflect/resource config for them.
  metadataRepository { enabled = true }

  binaries {
    named("main") {
      imageName = "opengb-server"
      // mainClass is inherited from the `application` plugin (org.opengb.AppKt).

      buildArgs.addAll(
        // No silent fallback to a JVM-backed image — a missing-metadata error should fail the
        // build so we notice and fix it, not ship a fat "native" image that embeds a JVM.
        "--no-fallback",
        // Fly machines span several CPU generations; target the baseline x86-64 ISA so the
        // binary runs regardless of which host it lands on.
        "-march=compatibility",
        // Kotlin's @Deprecated annotation references the DeprecationLevel enum, which
        // native-image evaluates at build time; declare it build-time-initialized so the
        // analysis doesn't flag it as "unintentionally initialized".
        "--initialize-at-build-time=kotlin.DeprecationLevel",
        // The outbound utility OAuth/usage calls go over HTTPS via the Ktor CIO client.
        "--enable-url-protocols=http,https",
        // Embed the app's own classpath resources. The tracing agent captures log4j2's config
        // (it logs during tests) but not these — the test suite builds AppConfig directly and
        // never serves the branding assets, so Hoplite's `/application.conf` + `/utilities.conf`
        // lookups and Ktor's staticResources under `static/` must be registered explicitly.
        "-H:IncludeResources=(application|utilities)\\.conf",
        "-H:IncludeResources=static/.*",
        // Bootable's log4j2 init merges our log4j2.xml with its own base config shipped inside
        // the boot-logging-log4j2 jar. The tests never call boot(), so the agent never traced
        // these — without them logging silently no-ops and swallows the real startup error.
        "-H:IncludeResources=log4j2-base.*\\.xml",
        // Surface real stack traces on uncaught exceptions instead of a bare error code.
        "-H:+ReportExceptionStackTraces",
      )

      // Statically link everything except glibc when `-Popengb.native.static` is set, so the
      // binary depends on libc alone and runs on a minimal distroless base (the alternative is
      // dragging libz.so.1 etc. into the runtime image). This needs static system libs
      // (zlib-static) present at build time, which the Dockerfile installs — so it's opt-in:
      // local `nativeCompile`/`nativeRun` link dynamically against the system's shared libs and
      // need nothing extra.
      if (providers.gradleProperty("opengb.native.static").isPresent) {
        buildArgs.add("-H:+StaticExecutableWithDynamicLibC")
      }
    }
  }

  // Reachability metadata is captured from TWO independent sources, each written to its own
  // subdirectory under META-INF/native-image/ (native-image unions every subdirectory at build
  // time, so there's no lossy agent merge between them — see scripts/generate-native-metadata.sh):
  //
  //   from-tests/ — `./gradlew -Pagent test` + `metadataCopy` below. Covers what the in-memory
  //                 test engine exercises: crypto, kotlinx.serialization, the OAuth flow, route
  //                 handlers. (No real sockets — tracing those is non-deterministic; the CIO
  //                 socket selector metadata comes from from-app instead.)
  //   from-app/   — a real boot() traced with the agent (the script). Covers what the test engine
  //                 can't: the CIO network selector (readHandlerReference), log4j2's JSON-config
  //                 plugin reflection, Hoplite's config decode, and Bootable startup.
  //
  // Re-run the script after dependency or code changes, then commit the regenerated files.
  agent {
    defaultMode = "standard"
    metadataCopy {
      inputTaskNames.add("test")
      outputDirectories.add("src/main/resources/META-INF/native-image/org.opengb/from-tests")
      // Overwrite, don't merge: from-tests/ is a clean snapshot of the agent's test run. Merging
      // would (a) require the target to already hold a complete config set — it fails reading a
      // missing proxy-config.json otherwise — and (b) accumulate stale entries that never prune,
      // which would defeat the CI metadata-drift guard.
      mergeWithExisting = false
    }
  }
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

  // The native-image tracing agent (enabled with `-Pagent`, used to regenerate reachability
  // metadata) ships ONLY inside a GraalVM JDK — but our default test JDK is temurin, so a bare
  // `./gradlew -Pagent test` fails with "Could not find agent library native-image-agent".
  // When -Pagent is set, fork the test JVM from GraalVM (GRAALVM_HOME, provided by mise — see
  // mise.toml) so the command works with no manual JAVA_HOME juggling. This is surgical: only the
  // agent run is affected; a normal `./gradlew test` stays on the temurin toolchain (matches CI).
  // Gradle doesn't auto-detect the mise GraalVM as a toolchain, so we point `executable` straight
  // at it rather than going through `javaLauncher`.
  if (providers.gradleProperty("agent").isPresent) {
    val graalHome =
      providers.environmentVariable("GRAALVM_HOME").orNull
        ?: error(
          "-Pagent needs a GraalVM JDK, but GRAALVM_HOME is unset. Run inside this repo with " +
            "mise active (it sets GRAALVM_HOME), or export it to a GraalVM for JDK 21.",
        )
    executable = "$graalHome/bin/java"
  }
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
