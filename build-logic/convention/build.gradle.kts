plugins {
    `kotlin-dsl`
}

group = "dev.hansel.buildlogic"

dependencies {
    compileOnly(libs.plugin.android.gradle)
    compileOnly(libs.plugin.kotlin.gradle)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "hansel.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "hansel.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "hansel.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("androidApplication") {
            id = "hansel.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
    }
}
