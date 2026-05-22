plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.power.assert) apply false
    alias(libs.plugins.testBalloon) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    group = "org.opengb"
    version = providers.gradleProperty("opengb.version").orElse("0.0.0-SNAPSHOT").get()
}
