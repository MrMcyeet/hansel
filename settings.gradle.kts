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

// Android modules
include(":storage")
include(":core-location")
include(":map-ui")
include(":app-car")
include(":app-phone")

// Phase 0 spike: on-device GraphHopper graph loading. Opt-in because it's a known-risk dependency.
if (providers.gradleProperty("hansel.spike.graphhopperOnDevice").orNull == "true") {
    include(":spikes:graphhopper-ondevice")
}
