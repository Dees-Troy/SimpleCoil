plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.simplecoil.simplecoil"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.simplecoil.simplecoil"
        minSdk {
            version = release(23)
        }
        targetSdk {
            version = release(37)
        }
        versionCode = 13
        versionName = "1.12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8: the app uses no reflection; keep build/outputs/mapping/release/mapping.txt with each release
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // Kotlin's jvmTarget follows targetCompatibility.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // What was left after the platform cleanup, map-owned warnings included (platform design §8.2)
        baseline = file("lint-baseline.xml")
        // The cleaned categories fail the build, so they don't come back
        error += setOf("HardcodedText", "SetTextI18n", "DefaultLocale", "UseSwitchCompatOrMaterialXml",
            "UseSwitchCompatOrMaterialCode")
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
