plugins {
    alias(libs.plugins.hansel.jvm.library)
}

// Note: inside module scripts `libs` resolves to build-logic's `Project.libs: VersionCatalog` extension (HanselConfig.kt),
// which shadows Gradle's generated type-safe accessors, so we go through findLibrary() instead of `libs.graphhopper.core`.
dependencies {
    api(project(":core-model"))

    // Generated protobuf classes for the OSM PBF format (Osmformat / Fileformat). Not in the version catalog on purpose:
    // this module is the only consumer. osmosis declares protobuf-java 3.12.2 (2020, several CVEs); we lift it to the
    // 3.25.x line, which is what the rest of the GraphHopper/Android toolchain already resolves to, and keep a single
    // protobuf major on the classpath. The protoc-3 generated classes in osmosis-osm-binary run unchanged on 3.25.
    implementation("org.openstreetmap.osmosis:osmosis-osm-binary:0.48.3")
    implementation("com.google.protobuf:protobuf-java:3.25.5")

    // Only to prove our writer's output is accepted by GraphHopper's own OSMInputFile; never shipped from here.
    testImplementation(libs.findLibrary("graphhopper-core").get()) {
        exclude(group = "ch.qos.logback")
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-xml")
        exclude(group = "org.apache.xmlgraphics")
    }
}
