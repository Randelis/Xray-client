plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace   = "com.xray.client"
    compileSdk  = 35

    defaultConfig {
        applicationId   = "com.xray.client"
        minSdk          = 26
        targetSdk       = 35
        versionCode     = 1
        versionName     = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled     = true
            isShrinkResources   = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Place ABI-split xray binaries in jniLibs so they're installed to /data/app/.../lib/
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    packaging {
        // MUST be true: the xray executable is shipped as libxray.so. With legacy
        // packaging the OS extracts native libs to nativeLibraryDir as real files,
        // which is what CoreManager copies out and exec()s. With it off the binary
        // stays compressed inside the APK and cannot be run.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)

    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)

    debugImplementation(libs.compose.ui.tooling)
}
