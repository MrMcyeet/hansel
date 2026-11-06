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
    implementation(libs.findLibrary("okhttp").get())
    implementation(libs.findLibrary("room-runtime").get())
    implementation(libs.findLibrary("room-ktx").get())
    ksp(libs.findLibrary("room-compiler").get())
    implementation(libs.findLibrary("androidx-work-runtime").get())
    implementation(libs.findLibrary("androidx-datastore-preferences").get())
    // GraphHopper core: the OSM importer (and osmosis-osm-binary for reading/writing PBF) live here.
    implementation(libs.findLibrary("graphhopper-core").get()) {
        exclude(group = "ch.qos.logback")
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-xml")
        exclude(group = "org.apache.xmlgraphics")
    }
    testImplementation(libs.findLibrary("okhttp-mockwebserver").get())
}
