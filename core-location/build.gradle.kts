plugins {
    alias(libs.plugins.hansel.android.library)
}

// `libs` here is build-logic's `Project.libs: VersionCatalog` (see routing-valhalla/build.gradle.kts), so use findLibrary().
dependencies {
    api(project(":core-model"))
    api(project(":core-navigation"))

    implementation(libs.findLibrary("play-services-location").get())
    implementation(libs.findLibrary("androidx-lifecycle-service").get())
    implementation(libs.findLibrary("kotlinx-coroutines-android").get())
}
