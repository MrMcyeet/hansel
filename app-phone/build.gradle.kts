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
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-routing-api"))
    implementation(project(":routing-valhalla"))
    implementation(project(":routing-graphhopper"))
    implementation(project(":core-navigation"))
    implementation(project(":search"))
    implementation(project(":benchmark"))
    implementation(project(":storage"))
    implementation(project(":core-location"))
    implementation(project(":map-ui"))
    implementation(project(":app-car"))

    implementation(libs.findLibrary("androidx-activity-compose").get())
    implementation(libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
    implementation(libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
    implementation(libs.findLibrary("androidx-navigation-compose").get())
    implementation(libs.findLibrary("compose-material-icons-extended").get())
    implementation(libs.findLibrary("okhttp").get())
    implementation(libs.findLibrary("okhttp-logging").get())
}
