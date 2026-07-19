plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.pokebuddy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pokebuddy"
        minSdk = 29          // Android 10 — MediaProjection FG service + background-launch restrictions era
        targetSdk = 35       // Android 15
        versionCode = 1
        versionName = "0.1.0-spike"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")

    // On-device text recognition. BUNDLED artifact = Latin model shipped inside the
    // APK → fully offline, no Google Play Services model download, no network call.
    // Satisfies the project's "never touch external servers" constraint.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Local index (SQLite via Room).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
}
