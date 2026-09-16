import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Optional release signing. Copy keystore.properties.sample -> keystore.properties and fill it in.
// IMPORTANT: every release MUST be signed with the same key, otherwise the in-app updater's
// install will fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.minimal.carlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.minimal.carlauncher"
        minSdk = 29
        // NOTE: deliberately NOT targetSdk 36. Android 16 ignores android:screenOrientation for
        // apps targeting 36 on displays with smallestWidth >= 600dp - which is exactly a head unit.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // TODO: point these at the repository that hosts the release APKs.
        buildConfigField("String", "GITHUB_OWNER", "\"Breakeridis\"")
        buildConfigField("String", "GITHUB_REPO", "\"open-car-launcher\"")

    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // No applicationIdSuffix on purpose: a debug install must be upgradable by a release APK.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
