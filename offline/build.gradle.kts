// Self-contained data pipeline: downloads OSM extracts, builds the routing graph, search index and map tiles on the
// phone, and activates them atomically. The heavy work runs in PackageBuildService in its own process.
plugins {
    alias(libs.plugins.hansel.android.library)
    alias(libs.plugins.ksp)
}

android {
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/9/module-info.class", "module-info.class")
    }
}

dependencies {
    api(project(":core-model"))
    api(project(":offline-osm"))
    api(project(":offline-tiles"))
    api(project(":routing-graphhopper"))
    implementation(libs.findLibrary("okhttp").get())
    implementation(libs.findLibrary("room-runtime").get())
    implementation(libs.findLibrary("room-ktx").get())
    ksp(libs.findLibrary("room-compiler").get())
    implementation(libs.findLibrary("androidx-lifecycle-service").get())
    testImplementation(libs.findLibrary("okhttp-mockwebserver").get())
}
