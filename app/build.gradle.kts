plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ro.netstabil.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ro.netstabil.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 13
        versionName = "1.6.0"

        vectorDrawables {
            useSupportLibrary = true
        }
        resourceConfigurations += listOf("en", "ro")
    }

    // F-Droid & IzzyOnDroid Reproducible Builds compliance:
    // Strip Google's encrypted DEPENDENCY_INFO_BLOCK (0x504b4453) from APK signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    val releaseKeystoreFile = rootProject.file("keystore/netstabil-release.jks")
    val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD")
    val hasReleaseSigning = releaseKeystoreFile.exists() && !releaseStorePassword.isNullOrBlank()

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = System.getenv("KEY_ALIAS") ?: "netstabil"
                keyPassword = System.getenv("KEY_PASSWORD") ?: releaseStorePassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    lint {
        xmlReport = true
        htmlReport = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Shizuku Privileged API (UID 2000 shell / UID 0 root without unlocking bootloader)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
