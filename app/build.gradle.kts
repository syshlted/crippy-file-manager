import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

// Kotlin support is built into AGP since 9.0; applying org.jetbrains.kotlin.android alongside it is an
// error, not a redundancy.
plugins {
    alias(libs.plugins.android.application)
}

val versions = libs.versions

android {
    namespace = "com.systemhalted.encri.files"
    compileSdk = versions.compileSdk.get().toInt()

    defaultConfig {
        // encri D124: the file manager is com.systemhalted.encri.files. Permanent from the first build a
        // user installs, because encri keys a grant on it (encri D120).
        applicationId = "com.systemhalted.encri.files"
        minSdk = versions.minSdk.get().toInt()
        targetSdk = versions.targetSdk.get().toInt()

        // encri D6's scheme, which this app shares: MAJOR*1000000 + MINOR*1000 + PATCH. encri keys a grant
        // on the version code too (encri D120), so every change to it asks the user again.
        versionCode = 1_000
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        // Robolectric reads the merged manifest and resources: the screens are under test (F1).
        unitTests.isIncludeAndroidResources = true
    }

    dependenciesInfo {
        // encri D116's setting: an APK signed by AGP would carry the dependency list encrypted to a key
        // only Google holds. Releases are signed outside Gradle, as encri's are, so this keeps an opaque
        // block out of any APK that is ever signed here. The bundle keeps its plaintext copy.
        includeInApk = false
        includeInBundle = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The only way this app reaches the vault (encri D112): the published client, never SAF.
    implementation(libs.encri.client)
    // Dispatchers.Main, which the client's coroutines need a main thread for on Android.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)

    // Robolectric is a JUnit 4 runner and the rest of the suite is JUnit 5, so the vintage engine runs
    // both under one task — encri's arrangement, for encri's reason: a module whose tests are split
    // across two tasks is one `allUnitTests` quietly covers half of.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Robolectric at SDK 36 on JDK 21 (encri-api L8).
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

// F1: the whole module, nothing excluded. The screens are kept thin and run under Robolectric, so the
// number says what was tested rather than what was easy to test.
kover {
    reports {
        verify {
            rule("F1: line coverage") { minBound(80, CoverageUnit.LINE) }
        }
    }
}
