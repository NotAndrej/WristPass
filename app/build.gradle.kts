plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is supplied out-of-band, never committed. Provide it either as
// environment variables or as Gradle properties:
//   WRISTPASS_STORE_FILE / wristpass.storeFile        path to the keystore
//   WRISTPASS_STORE_PASSWORD / wristpass.storePassword
//   WRISTPASS_KEY_ALIAS / wristpass.keyAlias
//   WRISTPASS_KEY_PASSWORD / wristpass.keyPassword
// With no keystore configured the minified release build still assembles and is
// simply left unsigned, which is what reproducible builds want.
val ksFile     = System.getenv("WRISTPASS_STORE_FILE")     ?: (findProperty("wristpass.storeFile")     as String?)
val ksPassword = System.getenv("WRISTPASS_STORE_PASSWORD") ?: (findProperty("wristpass.storePassword") as String?)
val ksAlias    = System.getenv("WRISTPASS_KEY_ALIAS")      ?: (findProperty("wristpass.keyAlias")      as String?)
val ksKeyPass  = System.getenv("WRISTPASS_KEY_PASSWORD")   ?: (findProperty("wristpass.keyPassword")   as String?)

android {
    namespace  = "com.notandrej.wristpass"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.notandrej.wristpass"
        minSdk        = 30
        targetSdk     = 34
        versionCode   = 1
        versionName   = "1.0"

        // The target watch is 32-bit (armeabi-v7a); ship only that ABI.
        ndk { abiFilters += "armeabi-v7a" }
    }

    signingConfigs {
        if (ksFile != null && ksPassword != null && ksAlias != null && ksKeyPass != null) {
            create("release") {
                storeFile     = file(ksFile)
                storePassword = ksPassword
                keyAlias      = ksAlias
                keyPassword   = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            // BouncyCastle is a multi-release jar; AGP does not need its module info.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/versions/9/module-info.class"
            excludes += "module-info.class"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    val lifecycle = "2.9.0"
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycle")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val wearCompose = "1.6.2"
    implementation("androidx.wear.compose:compose-material:$wearCompose")
    implementation("androidx.wear.compose:compose-foundation:$wearCompose")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")

    // Bitwarden protocol: plain HTTPS + form posts, no Retrofit needed.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // EncryptedSharedPreferences for the session tokens (Android Keystore backed).
    implementation("androidx.security:security-crypto:1.1.0")

    // PBKDF2-HMAC-SHA256 (byte-exact) and Argon2id for the master-key derivation.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")

    testImplementation("junit:junit:4.13.2")
}
