import java.io.FileInputStream
import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

/**
 * Secrets and machine-specific values live in local.properties, which is
 * gitignored.
 *
 * Gradle does NOT load local.properties into project properties automatically —
 * only gradle.properties is loaded that way. It has to be read explicitly, as
 * below. (This is why the previous `project.findProperty("google.maps.api.key")`
 * silently produced an empty key even though local.properties defined one.)
 */
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}

/** Reads from local.properties, falling back to a -P flag or gradle.properties. */
fun secret(key: String, default: String = ""): String =
    localProps.getProperty(key)
        ?: project.findProperty(key) as String?
        ?: default

// ✅ FIX: set buildDirectory at project scope to handle custom build-temp output folder
layout.buildDirectory.set(file("${rootDir}/build-temp"))

android {
    namespace = "com.techaus.afamfresh"
    // Bumped from 34 -> 36: Google Play requires new app submissions to
    // target Android 16 (API 36) starting Aug 31, 2026. compileSdk has to
    // rise with targetSdk (Android requires compileSdk >= targetSdk) — see
    // the matching comment on targetSdk below for what else this touches.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.techaus.afamfresh"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Defined in local.properties (gitignored) as:
        //     google.maps.api.key=AIza...
        val mapsApiKey = secret("google.maps.api.key")
        if (mapsApiKey.isEmpty()) {
            logger.warn(
                "WARNING: google.maps.api.key is not set in local.properties. " +
                    "Maps and geocoding will fail at runtime."
            )
        }
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$mapsApiKey\"")

        // Substituted into AndroidManifest.xml, so the literal key appears in
        // neither the manifest nor any source file.
        manifestPlaceholders["mapsApiKey"] = mapsApiKey
    }

    /**
     * One codebase, three installable apps.
     */
    flavorDimensions += "role"

    productFlavors {
        create("customer") {
            dimension = "role"
            resValue("string", "app_name", "AfamFresh")
            buildConfigField("String", "APP_ROLE", "\"user\"")
            manifestPlaceholders["deepLinkScheme"] = "afamfresh"
            buildConfigField("String", "DEEP_LINK_SCHEME", "\"afamfresh\"")
        }
        create("rider") {
            dimension = "role"
            applicationIdSuffix = ".rider"
            resValue("string", "app_name", "AfamFresh Rider")
            buildConfigField("String", "APP_ROLE", "\"rider\"")
            manifestPlaceholders["deepLinkScheme"] = "afamfresh-rider"
            buildConfigField("String", "DEEP_LINK_SCHEME", "\"afamfresh-rider\"")

            val riderMapsApiKey = secret("google.maps.api.key.rider", secret("google.maps.api.key"))
            manifestPlaceholders["mapsApiKey"] = riderMapsApiKey
            buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$riderMapsApiKey\"")
        }
        create("vendor") {
            dimension = "role"
            applicationIdSuffix = ".vendor"
            resValue("string", "app_name", "AfamFresh Vendor")
            buildConfigField("String", "APP_ROLE", "\"vendor\"")
            manifestPlaceholders["deepLinkScheme"] = "afamfresh-vendor"
            buildConfigField("String", "DEEP_LINK_SCHEME", "\"afamfresh-vendor\"")
        }
    }

    val releaseStoreFile = secret("release.store.file")
    val hasSigningConfig = releaseStoreFile.isNotEmpty() && file(releaseStoreFile).exists()

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = secret("release.store.password")
                keyAlias = secret("release.key.alias")
                keyPassword = secret("release.key.password")
            }
        }
    }

    buildTypes {
        debug {
            val debugBaseUrl = secret("base.url.debug", "http://10.0.2.2/afamfresh/api/")
            buildConfigField("String", "BASE_URL", "\"$debugBaseUrl\"")
            buildConfigField("String", "NOMINATIM_BASE_URL", "\"${secret("nominatim.url.debug", "https://nominatim.openstreetmap.org/")}\"")
            buildConfigField("String", "OSRM_BASE_URL", "\"https://router.project-osrm.org/\"")
            manifestPlaceholders["appLinkHost"] = runCatching { URI(debugBaseUrl).host }.getOrNull() ?: "localhost"
        }

        release {
            val releaseBaseUrl = secret("base.url.release", "https://afamfresh-backend.onrender.com/api/")
            buildConfigField("String", "BASE_URL", "\"$releaseBaseUrl\"")
            buildConfigField("String", "NOMINATIM_BASE_URL", "\"${secret("nominatim.url.release", "https://nominatim.openstreetmap.org/")}\"")
            buildConfigField("String", "OSRM_BASE_URL", "\"https://router.project-osrm.org/\"")
            manifestPlaceholders["appLinkHost"] = runCatching { URI(releaseBaseUrl).host }.getOrNull() ?: "afamfresh-backend.onrender.com"

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "WARNING: no release signing config found in local.properties. " +
                        "The release APK will be unsigned and cannot be installed or published."
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true

            all {
                it.testLogging {
                    events("passed", "skipped", "failed")
                }
            }
        }
    }
}

/**
 * Automates copying the customer release APK directly to the TechAus Web downloads directory.
 */
tasks.register<Copy>("copyCustomerReleaseApk") {
    dependsOn("assembleCustomerRelease")

    from(layout.buildDirectory.file("outputs/apk/customer/release/app-customer-release.apk"))
    into(file("/Users/wanacel/Desktop/Techaus Web/downloads"))

    rename { "afamfresh-v1.2.1.apk" }

    doLast {
        copy {
            from(layout.buildDirectory.file("outputs/apk/customer/release/app-customer-release.apk"))
            into(file("/Users/wanacel/Desktop/Techaus Web/downloads"))
            rename { "afamfresh-latest.apk" }
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Location services for GPS detection (customer + rider)
    implementation("com.google.android.gms:play-services-location:21.3.0")
    "riderImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Maps
    implementation("com.google.maps.android:maps-compose:4.3.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-messaging")

    // Google Sign-In & Credentials
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Encrypted storage for auth tokens
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.11.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}