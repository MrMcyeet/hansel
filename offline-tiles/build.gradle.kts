plugins {
    alias(libs.plugins.hansel.jvm.library)
}

// Note: inside module scripts `libs` resolves to build-logic's `Project.libs: VersionCatalog` extension (HanselConfig.kt),
// which shadows Gradle's generated type-safe accessors, so we go through findLibrary() instead of `libs.okhttp`.
dependencies {
    api(project(":core-model"))
    implementation(libs.findLibrary("okhttp").get())

    testImplementation(libs.findLibrary("okhttp-mockwebserver").get())
}
