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

powerAssert {
  functions = listOf("kotlin.assert")
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

  detektPlugins(libs.detekt.formatting)
}

tasks.test {
  useJUnitPlatform()
}
