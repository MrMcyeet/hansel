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
            // Personal app: R8-minified so the APK is a sane size, signed with the debug key so `adb install` just works.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // The target phone is arm64 only; MapLibre's native libs for the other three ABIs are ~80 MB of dead weight.
    // Pass -Phansel.universalApk=true to get the all-ABI build back (emulators on x86_64 need it).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = providers.gradleProperty("hansel.universalApk").orNull == "true"
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
