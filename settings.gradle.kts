pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "hansel"

// Pure-JVM modules: routing logic, navigation state machine, adapters. Fast unit tests, no SDK needed.
include(":core-model")
include(":core-routing-api")
include(":routing-valhalla")
include(":routing-graphhopper")
include(":core-navigation")
include(":search")
include(":benchmark")
include(":offline-osm")
include(":offline-tiles")

// Android modules
include(":storage")
include(":core-location")
include(":map-ui")
include(":app-car")
include(":app-phone")

// Self-contained mode (Phase 3 pulled forward): on-device data pipeline and in-process GraphHopper.
include(":offline")
include(":routing-graphhopper-local")
