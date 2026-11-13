plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.airlink"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.airlink"
        minSdk = 24
        targetSdk = 34
        // Bump these together for every release: the label carries the version.
        versionCode = 7
        versionName = "4.3"
    }

    // One committed key for every build, so v2 installs straight over v1.
    // It is a sideloading key, not a Play key — it lives in the repo and its
    // password is public on purpose. Never use it for anything you publish.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/airlink.p12")
            storeType = "PKCS12"
            storePassword = "airlink"
            keyAlias = "airlink"
            keyPassword = "airlink"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            // R8 + resource shrinking: this is the build that gets handed out.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("shared")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
