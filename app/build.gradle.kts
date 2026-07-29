import java.io.FileInputStream
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

// ✅ FIX: was `buildDir = file("${rootDir}/build-temp")` INSIDE the android{} block.
// Two problems with that:
//   1. `buildDir` is a Project property, not an Android extension property — it only
//      compiled there by leaking through to the outer scope. Confusing, and it meant
//      the build dir was being set midway through configuration.
//   2. The `buildDir` setter is deprecated in Gradle 8 and REMOVED in Gradle 9.
// This is the modern equivalent, at project scope where it belongs.
layout.buildDirectory.set(file("${rootDir}/build-temp"))

android {
    namespace = "com.techaus.afamfresh"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.techaus.afamfresh"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

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
     * Release signing is configured only when the keystore details are present
     * in local.properties, so a plain `assembleDebug` still works on a machine
     * that has never generated a key.
     *
     * To produce one:
     *   keytool -genkey -v -keystore afamfresh-release.jks \
     *           -keyalg RSA -keysize 2048 -validity 10000 -alias afamfresh
     *
     * Then add to local.properties (NEVER commit the .jks or these values):
     *   release.store.file=C:/path/to/afamfresh-release.jks
     *   release.store.password=...
     *   release.key.alias=afamfresh
     *   release.key.password=...
     *
     * Keep a backup of the keystore. Losing it means you can never publish an
     * update to the same Play listing again.
     */
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
            // Points at the dev machine on the local network.
            buildConfigField("String", "BASE_URL", "\"${secret("base.url.debug", "http://192.168.3.41/api/")}\"")
            buildConfigField("String", "NOMINATIM_BASE_URL", "\"${secret("nominatim.url.debug", "http://192.168.3.41:8080/")}\"")
            buildConfigField("String", "OSRM_BASE_URL", "\"https://router.project-osrm.org/\"")
        }

        release {
            // HTTPS only. The network security config forbids cleartext in
            // release, so an http:// URL here would fail at runtime rather
            // than silently sending customer data in the clear.
            buildConfigField("String", "BASE_URL", "\"${secret("base.url.release", "https://afam.techaus.online/api/")}\"")
            buildConfigField("String", "NOMINATIM_BASE_URL", "\"${secret("nominatim.url.release", "https://afam.techaus.online:8080/")}\"")
            buildConfigField("String", "OSRM_BASE_URL", "\"https://router.project-osrm.org/\"")

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
        // Requires Kotlin 1.9.24 — matches the root build.gradle.kts.
        // If you ever bump Kotlin, this must be bumped in lockstep.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Android framework classes are stubs in JVM unit tests, and by
            // default every call to one throws. That makes an otherwise pure
            // function untestable the moment it logs — DeliveryConfig's
            // validation calls android.util.Log.w on the rejection paths,
            // which are exactly the paths worth testing.
            //
            // Returning defaults means those calls become no-ops instead.
            isReturnDefaultValues = true

            all {
                it.testLogging {
                    events("passed", "skipped", "failed")
                }
            }
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
    // Added defensively: `viewModelScope` (used in AuthViewModel/PaymentViewModel)
    // lives here. It's very likely already arriving transitively via the artifacts
    // above, but declaring it explicitly costs nothing and removes the doubt.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // OSMDroid
    implementation("org.osmdroid:osmdroid-android:6.1.17")
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.17")

    // Google Location Services
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Google Maps Compose
    implementation("com.google.maps.android:maps-compose:4.3.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Permissions Helper
    implementation("com.google.accompanist:accompanist-permissions:0.35.0-alpha")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-messaging")

    // Google Sign‑In
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Biometric
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Encrypted storage for the auth token and session cookie
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Needed for runTest / TestDispatcher. The plan assumed this was already
    // present; it was not.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.11.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
