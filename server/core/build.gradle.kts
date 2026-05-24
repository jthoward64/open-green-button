import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.testBalloon)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("../config/detekt.yml"))
}

tasks.withType<Detekt>().configureEach { jvmTarget = "21" }
tasks.withType<DetektCreateBaselineTask>().configureEach { jvmTarget = "21" }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.xmlutil.core)
    implementation(libs.xmlutil.serialization)

    testImplementation(libs.testBalloon.framework.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
