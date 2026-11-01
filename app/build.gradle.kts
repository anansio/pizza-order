plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.kaerbr.pizzaorder"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.kaerbr.pizzaorder"
        minSdk = 26
        targetSdk = 37
        // versionName is rewritten by semantic-release (.releaserc.yaml); versionCode follows it: 1.2.3 -> 10203.
        versionName = "1.8.1"
        versionCode = versionName!!.split(".").fold(0) { code, part -> code * 100 + part.toInt() }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Only CI has the release keystore (GitHub secrets); local release builds stay unsigned.
    val keystoreFile = System.getenv("KEYSTORE_FILE")
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Own app id, so a test APK from a PR installs next to the release app instead of replacing it
        // (different signing key) and keeps its own orders and menu.
        debug {
            applicationIdSuffix = ".debug"
        }
        // R8 compiles Compose into much faster code: without it scrolling and animations stutter on
        // slower tablets. Debug builds stay unoptimized and are no measure of how smooth the app runs.
        release {
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Release build (R8) signed with the debug key, as a PR test APK: shows how smooth the real app
        // runs, which the debug APK can't. Own app id, next to both the release and the debug app.
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".preview"
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dantsu.escposThermalprinter)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
// Unit tests gate every debug build, Android Studio's Run included: MenuConfigTest turns an invalid
// MenuConfig.DEFAULT into a build error instead of a crash on app start.
// ponytail: runs the whole (small) suite; up-to-date when nothing changed, so near-free on rebuilds.
tasks.matching { it.name == "assembleDebug" }.configureEach { dependsOn("testDebugUnitTest") }
// Print the exception message ("Ungültiges Menü: …") in the build output, not just its class name.
tasks.withType<Test>().configureEach { testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
