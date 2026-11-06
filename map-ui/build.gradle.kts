plugins {
    alias(libs.plugins.hansel.android.library.compose)
}

dependencies {
    api(project(":core-model"))
    api(project(":core-navigation"))
    api(libs.findLibrary("maplibre-android").get())
}
