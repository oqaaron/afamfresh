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
        // MUST increase for every build handed to anyone. Android treats a
        // lower versionCode as a downgrade and refuses to install it, and Play
        // rejects a re-used one outright.
        //
        // It has no bearing on whether app data survives an install — that is
        // decided by the signing certificate. Same key means an update, and
        // accounts, sessions and cached state are kept; a different key is a
        // different app and cannot install over this one at all. Which is why
        // ~/afamfresh-release.jks is irreplaceable: lose it and these three
        // apps can never be updated again, only republished under new package
        // names, with every existing install stranded.
        versionCode = 2
        versionName = "1.0.1"

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
     *
     * Product flavors rather than separate projects: only ~11% of this codebase
     * is role-specific. The other ~89% — models, api, repositories, viewmodels,
     * auth, theme — would have to be copied and separately maintained in three
     * projects, and this codebase has already paid that price once (endpoints
     * the app called that never existed, images returned in an unusable form,
     * `current_role` read in a way that silently returned NULL).
     *
     * Each flavor gets its own applicationId, so all three install side by side
     * and each has its own Play listing, icon and notification identity.
     *
     * APP_ROLE is the single constant shared code branches on. It matches the
     * `user_roles.role` values the server already validates against — the apps
     * are a UX split, NOT a security boundary: api/rider.php and friends still
     * check the session's role server-side, because anyone can call them
     * directly.
     */
    flavorDimensions += "role"

    productFlavors {
        create("customer") {
            dimension = "role"
            // NOT namespaced. Changing this would orphan every existing install
            // and register as a brand-new app on Play.
            // applicationId stays the defaultConfig value.
            resValue("string", "app_name", "AfamFresh")
            buildConfigField("String", "APP_ROLE", "\"user\"")
            // Each app owns its own deep-link scheme. Sharing one would make
            // Android show an app-chooser on every password-reset link.
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

            // Independent Maps key so rider's Android-app restrictions
            // (package+SHA-1) live on their own key, not tangled up with
            // customer/vendor's. Falls back to the shared defaultConfig key
            // if google.maps.api.key.rider isn't set in local.properties yet,
            // so nothing breaks before that key is created.
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
            // Set base.url.debug in local.properties to your dev machine's
            // address. The default below is the EMULATOR's alias for the host
            // (10.0.2.2), because that is the only value which is correct
            // without knowing the machine — a hardcoded LAN IP goes stale the
            // moment DHCP hands out a different lease, which is exactly what
            // happened to the previous default of 192.168.3.41.
            //
            // Note the path: afamfresh is a subdirectory of htdocs with no
            // Apache vhost, so /api/ alone returns 404.
            val debugBaseUrl = secret("base.url.debug", "http://10.0.2.2/afamfresh/api/")
            buildConfigField("String", "BASE_URL", "\"$debugBaseUrl\"")

            // Public Nominatim by default. The previous default pointed at a
            // self-hosted instance on port 8080 that is not running.
            buildConfigField("String", "NOMINATIM_BASE_URL", "\"${secret("nominatim.url.debug", "https://nominatim.openstreetmap.org/")}\"")
            buildConfigField("String", "OSRM_BASE_URL", "\"https://router.project-osrm.org/\"")

            // Android App Links host for the password-reset intent-filter (see
            // AndroidManifest.xml). Derived from BASE_URL rather than a second
            // local.properties key, so the two can't drift apart. Debug's
            // default (10.0.2.2, plain http) will never pass Digital Asset
            // Links verification -- that's fine, the custom-scheme intent
            // filter still covers debug/local testing; only a real https host
            // (as release should always be) can host assetlinks.json at all.
            manifestPlaceholders["appLinkHost"] = runCatching { URI(debugBaseUrl).host }.getOrNull() ?: "localhost"
        }

        release {
            // HTTPS only. The network security config forbids cleartext in
            // release, so an http:// URL here would fail at runtime rather
            // than silently sending customer data in the clear.
            // ⚠️ UNVERIFIED. If production mirrors this dev machine's layout,
            // the path needs the /afamfresh/ segment as well — check with
            // `curl -s -o /dev/null -w "%{http_code}" https://afam.techaus.online/api/products.php?action=list`
            // before shipping a release build.
            val releaseBaseUrl = secret("base.url.release", "https://afam.techaus.online/api/")
            buildConfigField("String", "BASE_URL", "\"$releaseBaseUrl\"")

            // Was https://afam.techaus.online:8080/ — a guessed self-hosted
            // Nominatim. No such instance was found running anywhere, so this
            // defaults to the public service. If you do stand one up, set
            // nominatim.url.release in local.properties.
            buildConfigField("String", "NOMINATIM_BASE_URL", "\"${secret("nominatim.url.release", "https://nominatim.openstreetmap.org/")}\"")
            buildConfigField("String", "OSRM_BASE_URL", "\"https://router.project-osrm.org/\"")

            // See the matching debug comment: this MUST be the real host
            // release builds ship with (whatever base.url.release resolves
            // to), or the App Link will never verify and every reset link
            // falls back to the custom-scheme handoff instead. If
            // base.url.release in local.properties differs from what's
            // actually live, assetlinks.json (api/.well-known/assetlinks.json)
            // needs to be hosted on THAT host, not this build's guess.
            manifestPlaceholders["appLinkHost"] = runCatching { URI(releaseBaseUrl).host }.getOrNull() ?: "afam.techaus.online"

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

    // OSMDroid — REMOVED.
    //
    // Nothing in the app ever imported osmdroid or mapsforge; maps are drawn
    // with Google Maps Compose (below). Beyond being dead weight, these broke
    // the release build outright:
    //
    //   ERROR: R8: Library class android.content.res.XmlResourceParser
    //          implements program class org.xmlpull.v1.XmlPullParser
    //
    // osmdroid-mapsforge pulls in net.sf.kxml:kxml2:2.3.0, which bundles its own
    // copy of org.xmlpull.v1 — classes the Android platform already provides.
    // R8 refuses to shrink an app where a platform class implements an
    // app-bundled interface, so assembleRelease failed while assembleDebug (no
    // R8) passed. That is why this only appeared once the release build was run.
    //
    // If OSM tiles are ever wanted, re-add osmdroid-android only, and exclude
    // the conflicting group:
    //   implementation("org.osmdroid:osmdroid-mapsforge:6.1.17") {
    //       exclude(group = "net.sf.kxml", module = "kxml2")
    //   }

    // Scoped per flavor, because each of these SDKs is used by exactly one
    // screen and that screen now lives in one flavor's source set:
    //
    //   Location  -> src/rider/.../ui/screens/rider/RiderLocationUpdates.kt
    //   Maps      -> src/customer/.../ui/screens/DeliveryMapScreen.kt
    //
    // If a stray reference to either ever appears in src/main, the build
    // breaks for the flavors that no longer have the dependency — which is the
    // point: the isolation is enforced by the compiler, not by convention.
    // Location services for GPS detection (customer + rider)
    implementation("com.google.android.gms:play-services-location:21.3.0")
    "riderImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Maps, in all three apps.
    //
    // These were flavour-scoped, and the argument for that was sound while ONE
    // screen in ONE app drew a map: compiler-enforced isolation for a dependency
    // most of the build did not need. All three draw maps now — the customer
    // tracks a delivery, the vendor pins their premises, the rider navigates —
    // so scoping would mean the shared map package could not live in src/main
    // and the same styling, polyline and marker code would be copy-pasted into
    // three source sets. That is precisely the failure mode this file already
    // documents having paid for once.
    //
    // play-services-location stays rider-only below: only the rider streams a
    // position.
    implementation("com.google.maps.android:maps-compose:4.3.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // accompanist-permissions was removed: nothing in the app referenced it.
    // Runtime permissions are requested directly through
    // ActivityResultContracts (see ui/screens/rider/RiderLocationUpdates.kt).

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

    // androidx.biometric was removed: zero references anywhere in the source.
    // The app has no biometric unlock; sessions are protected by
    // EncryptedSharedPreferences instead (see utils/SecurePrefs.kt).

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
