import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** Shared Android/Kotlin configuration. Target: Galaxy S25+ on Android 17; we compile against API 36. */
object HanselConfig {
    const val COMPILE_SDK = 36
    const val TARGET_SDK = 36
    /** Single-owner app on a current flagship; no reason to carry old-API shims. */
    const val MIN_SDK = 31
    val JAVA = JavaVersion.VERSION_17
    val JVM_TARGET = JvmTarget.JVM_17
}

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(HanselConfig.JVM_TARGET)
            freeCompilerArgs.add("-Xcontext-parameters")
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }
}

fun Project.configureAndroidCommon(ext: CommonExtension<*, *, *, *, *, *>) {
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
