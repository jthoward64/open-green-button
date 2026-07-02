@file:Suppress("UnstableApiUsage")

rootProject.name = "open-green-button-server"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // TEMPORARY (verification): resolve the local bootable 2.2.0-SNAPSHOT that bundles its own
        // native-image reachability metadata. Revert once bootable 2.2.0 is released to Maven Central.
        mavenLocal()
        mavenCentral()
    }
}

include(":app")
