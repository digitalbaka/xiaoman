import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Copies `skills/examples/<name>/...` into an asset folder `skills/<name>/...` of the APK. */
abstract class BundleSkillsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val examples: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun bundle() {
        fs.sync {
            from(examples)
            into(output.dir("skills"))
            exclude("**/.*")
        }
    }
}

val bundleSkills = tasks.register<BundleSkillsTask>("bundleSkillAssets") {
    examples.set(rootProject.layout.projectDirectory.dir("skills/examples"))
    output.set(layout.buildDirectory.dir("generated/skill-assets"))
}

android {
    namespace = "dev.xiaoman.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.xiaoman.app"
        minSdk = 25
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
    }
    // Local fixed debug key, kept out of the repo: signing.local.properties (git-ignored) holds the
    // keystore path and passwords, gradle.properties only names that file. Loading it on every build
    // keeps one certificate on the device, so debug builds overwrite the installed app instead of
    // forcing an uninstall that wipes the data. When the file is missing the stock debug signing stays.
    val signingFile = rootProject.file(
        (project.findProperty("xiaoman.signing.file") as String?) ?: "signing.local.properties",
    )
    val localSigning = Properties().apply {
        if (signingFile.isFile) signingFile.inputStream().use { load(it) }
    }
    fun localSigningValue(name: String) = localSigning.getProperty(name)?.takeIf { it.isNotBlank() }
    val localDebugKeystore = localSigningValue("storeFile")
    val localDebugStorePassword = localSigningValue("storePassword")
    val localDebugKeyAlias = localSigningValue("keyAlias")
    val localDebugKeyPassword = localSigningValue("keyPassword")
    signingConfigs {
        if (localDebugKeystore != null && localDebugStorePassword != null &&
            localDebugKeyAlias != null && localDebugKeyPassword != null
        ) {
            create("localDebug") {
                storeFile = rootProject.file(localDebugKeystore)
                storePassword = localDebugStorePassword
                keyAlias = localDebugKeyAlias
                keyPassword = localDebugKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            signingConfigs.findByName("localDebug")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time / java.util.Base64 on Android 7.1 (API 25).
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin") }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleSkills, BundleSkillsTask::output)
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core-api"))
    implementation(project(":core-llm"))
    implementation(project(":core-memory"))
    implementation(project(":core-skills"))
    implementation(project(":core-sentinel"))
    implementation(project(":core-agent"))
    implementation(project(":hands"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
