plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.testBalloon)
    alias(libs.plugins.jib)
    alias(libs.plugins.ktlint)
    application
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
    functions =
        listOf(
            "kotlin.assert",
            "kotlin.test.assertTrue",
            "kotlin.test.assertEquals",
            "kotlin.test.assertNotNull",
            "kotlin.test.assertNull",
            "kotlin.test.assertContains",
        )
    includedSourceSets = listOf("test")
}

dependencies {
    implementation(project(":core"))

    // Bootable bootstrap
    implementation(libs.bootable.boot)
    implementation(libs.bootable.config.common)
    implementation(libs.bootable.config.hoplite)
    implementation(libs.bootable.log4j2)

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
}

tasks.test {
    useJUnitPlatform()
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
    }
    container {
        mainClass = "org.opengb.AppKt"
        ports = listOf("8080")
        jvmFlags =
            listOf(
                "-XX:+UseSerialGC",
                "-Xmx192m",
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
