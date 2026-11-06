// Phase 0 spike (DESIGN §4.3, §14 "Both routing engines truly offline on Android"). Opt-in:
//   ./gradlew -Phansel.spike.graphhopperOnDevice=true :spikes:graphhopper-ondevice:assembleDebug
// GraphHopper's repository states offline mobile routing is no longer officially supported; this module exists to
// *measure* whether the Java core still loads a graph and routes on the phone. Nothing in the app depends on it.
plugins {
    alias(libs.plugins.hansel.android.library)
}

android {
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/9/module-info.class", "module-info.class")
    }
}

dependencies {
    api(project(":core-model"))
    api(project(":core-routing-api"))
    implementation(project(":routing-graphhopper")) // reuse the sign→ManeuverType table
    implementation(libs.findLibrary("graphhopper-core").get()) {
        // Server-only transitive baggage that does not belong in an APK.
        exclude(group = "ch.qos.logback")
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-xml")
        exclude(group = "org.apache.xmlgraphics")
    }
}
