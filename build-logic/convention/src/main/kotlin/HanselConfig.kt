import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Shared Android/Kotlin configuration. Target: Galaxy S25+ on Android 17 (API 37). AGP 9 provides built-in Kotlin,
 * so no `org.jetbrains.kotlin.android` plugin is applied anywhere.
 */
object HanselConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    /** Single-owner app on a current flagship; no reason to carry old-API shims. */
    const val MIN_SDK = 31
    val JAVA = JavaVersion.VERSION_17
    val JVM_TARGET = JvmTarget.JVM_17
}

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Works for both KGP's KotlinCompile (JVM modules) and AGP 9's built-in Kotlin compile tasks. */
fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(HanselConfig.JVM_TARGET)
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }
}

fun Project.configureAndroidLibrary(ext: LibraryExtension) {
    ext.apply {
        compileSdk = HanselConfig.COMPILE_SDK
        defaultConfig {
            minSdk = HanselConfig.MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = HanselConfig.JAVA
            targetCompatibility = HanselConfig.JAVA
        }
        lint {
            abortOnError = false
            warningsAsErrors = false
        }
    }
    configureKotlinJvmTarget()
}

fun Project.configureAndroidApplication(ext: ApplicationExtension) {
    ext.apply {
        compileSdk = HanselConfig.COMPILE_SDK
        defaultConfig {
            minSdk = HanselConfig.MIN_SDK
            targetSdk = HanselConfig.TARGET_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = HanselConfig.JAVA
            targetCompatibility = HanselConfig.JAVA
        }
        lint {
            abortOnError = false
            warningsAsErrors = false
        }
    }
    configureKotlinJvmTarget()
}
