plugins {
    alias(libs.plugins.hansel.android.library)
}

// `libs` in module scripts resolves to build-logic's `Project.libs: VersionCatalog` (see routing-valhalla), so catalog
// entries go through findLibrary(). kotlinx-coroutines-android, core-ktx and the JUnit/kotlin-test deps come from the
// hansel.android.library convention plugin.
dependencies {
    api(project(":core-model"))
    api(project(":core-navigation"))

    implementation(libs.findLibrary("car-app").get())
    implementation(libs.findLibrary("car-app-projected").get())
    implementation(libs.findLibrary("kotlinx-coroutines-android").get())
    implementation(libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
}
