import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/** Pure-Kotlin JVM library: no Android dependency, fast `test` task. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = HanselConfig.JAVA
            targetCompatibility = HanselConfig.JAVA
        }
        configureKotlinJvmTarget()

        dependencies {
            add("implementation", libs.findLibrary("kotlinx-coroutines-core").get())
            add("implementation", libs.findLibrary("kotlinx-serialization-json").get())
            add("testImplementation", libs.findLibrary("kotlin-test").get())
            add("testImplementation", libs.findLibrary("kotlin-test-junit").get())
            add("testImplementation", libs.findLibrary("junit").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        }

        tasks.withType<Test>().configureEach {
            useJUnit()
            testLogging { events("failed", "skipped") ; showStandardStreams = false }
        }
    }
}
