plugins {
    alias(libs.plugins.hansel.android.application)
}

android {
    namespace = "dev.hansel"
    defaultConfig {
        applicationId = "dev.hansel"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":core-model"))
}
