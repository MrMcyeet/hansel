plugins {
    alias(libs.plugins.hansel.android.library)
    alias(libs.plugins.ksp)
}

// `libs` here is build-logic's `Project.libs: VersionCatalog` (see routing-valhalla/build.gradle.kts), so use findLibrary().
dependencies {
    api(project(":core-model"))
    api(project(":core-navigation"))
    api(project(":core-routing-api"))
    api(project(":benchmark"))

    implementation(libs.findLibrary("room-runtime").get())
    implementation(libs.findLibrary("room-ktx").get())
    ksp(libs.findLibrary("room-compiler").get())
    implementation(libs.findLibrary("androidx-datastore-preferences").get())
    implementation(libs.findLibrary("kotlinx-serialization-json").get())
    implementation(libs.findLibrary("kotlinx-coroutines-core").get())
}
