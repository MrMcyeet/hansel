// GraphHopper's Java core running inside the app: loads a graph built on the phone (see :offline) and routes offline.
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
    api(libs.findLibrary("graphhopper-core").get()) {
        // Server-only transitive baggage that does not belong in an APK.
        exclude(group = "ch.qos.logback")
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-xml")
        exclude(group = "org.apache.xmlgraphics")
    }
}
