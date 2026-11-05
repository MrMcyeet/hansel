plugins {
    alias(libs.plugins.hansel.jvm.library)
}

dependencies {
    api(project(":core-model"))
    api(project(":core-routing-api"))
}
