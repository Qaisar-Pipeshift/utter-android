plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes only from CI secrets (docs/spec.md §11); local and PR builds stay unsigned.
val keystoreFile: String? = System.getenv("UTTER_KEYSTORE_FILE")

android {
    namespace = "io.github.qaisarpipeshift.utter"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.qaisarpipeshift.utter"
        minSdk = 28
        targetSdk = 36
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0-dev"
        buildConfigField("String", "GIT_SHA", "\"${System.getenv("GITHUB_SHA")?.take(7) ?: "local"}\"")
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("UTTER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UTTER_KEY_ALIAS")
                keyPassword = System.getenv("UTTER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric renders real Compose pixels for the screenshot tests, so it needs the app's resources.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // The Compose test host activity, present only in debug builds and on the unit-test classpath.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Screenshot tests (approved test-only deps, docs/spec.md §5): Robolectric renders Compose to PNGs.
    testImplementation(composeBom)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}

// CI is the only place tests run, so a failure must print its full message and stack trace in the log.
tasks.withType<Test>().configureEach {
    // Robolectric's SDK 36 sandbox reflects into java.io.FileDescriptor; JDK 21 modules must open it to the tests.
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
    // Record mode without the Roborazzi Gradle plugin: every captureRoboImage call writes its PNG.
    systemProperty("roborazzi.test.record", "true")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}
