package com.techaus.afamfresh.api

import android.content.Context
import com.google.gson.GsonBuilder
import com.techaus.afamfresh.BuildConfig
import com.techaus.afamfresh.utils.NetworkStatus
import com.techaus.afamfresh.utils.SessionTracker
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    // URLs come from the build type rather than commented-out blocks that had
    // to be edited by hand — debug points at the local dev server, release at
    // production. Both are defined in app/build.gradle.kts and overridable
    // from local.properties.
    private val BASE_URL = BuildConfig.BASE_URL
    private val NOMINATIM_BASE_URL = BuildConfig.NOMINATIM_BASE_URL

    // ✅ FIX: was "https://router.project-osrm.org/:5000/". The ":5000" is a
    // stray path segment, not a port — a port has to sit directly after the
    // host — so every routing request was hitting a non-existent path and
    // silently falling back to straight-line distance. That undercharges for
    // deliveries, because road distance always exceeds the direct line.
    private val OSRM_BASE_URL = BuildConfig.OSRM_BASE_URL

    // Google Geocoding
    private const val GOOGLE_GEOCODING_BASE_URL = "https://maps.googleapis.com/"

    /**
     * Read from BuildConfig, which is populated from local.properties at build
     * time. The literal key is no longer present in source or in the manifest.
     */
    val GOOGLE_MAPS_API_KEY: String = BuildConfig.GOOGLE_MAPS_API_KEY
    
    /**
     * The boolean adapters are not optional. The backend returns MySQL
     * `tinyint(1)` columns as the integers 0/1, which Gson's default Boolean
     * adapter rejects with a JsonSyntaxException — and that aborts the entire
     * response, not just the one field. See [MySqlBooleanAdapter].
     *
     * Both the primitive and the boxed type must be registered; Gson treats
     * `Boolean` and `boolean` as distinct types, and Kotlin's non-null `Boolean`
     * maps to the primitive.
     */
    private val gson = afamFreshGson()
    
    /**
     * BODY logging serialises every request and response body in full. In a
     * release build that is two separate problems:
     *
     *  1. It writes auth tokens, passwords and customer addresses into logcat,
     *     where any app with READ_LOGS or anyone with adb can read them.
     *  2. It costs real CPU and allocation on every single call, for output
     *     nobody will ever look at.
     *
     * NONE in release leaves the interceptor in the chain but makes it a no-op,
     * so the client is otherwise configured identically across build types.
     */
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }
    
    private lateinit var context: Context

    /**
     * Watches every response for two things:
     *
     *  - a 401, meaning the session has lapsed. Catching it here works no
     *    matter which screen or repository made the call, so the app can sign
     *    the user out once and explain why, rather than every screen failing
     *    separately with an unexplained error.
     *
     *  - a success, treated as evidence the user is active. This is what makes
     *    the 30-minute idle timeout measure real idleness rather than time
     *    since login.
     */
    private val sessionInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        // Scoped to our own backend ONLY. This one OkHttpClient is shared by
        // four Retrofit instances — the app API, OSRM, Nominatim and Google
        // Geocoding — and only the first of those knows anything about the
        // user's session.
        //
        // Without this guard a 401 from Google Geocoding (an expired or
        // over-quota Maps key) would sign the customer out of AfamFresh
        // mid-checkout, and a successful OSRM lookup would keep a stale
        // session alive on third-party traffic.
        //
        // Matching on the full BASE_URL prefix rather than the host. This used
        // to matter because Nominatim was configured on the same host at a
        // different port; it now points at nominatim.openstreetmap.org, but the
        // prefix check stays because the app API lives under a subdirectory
        // (/afamfresh/api/) and a host-only match would also catch anything else
        // served from the same dev machine.
        if (request.url.toString().startsWith(BASE_URL)) {
            when {
                response.code == 401 -> SessionTracker.notifyExpired()
                response.isSuccessful -> SessionTracker.touch()
            }
        }
        response
    }

    /**
     * Held rather than constructed inline so the session cookie can actually
     * be cleared. Dropping the auth token without dropping PHPSESSID leaves
     * the app still presenting the old server session — on logout that means
     * the next user of a shared device inherits it until the server expires
     * it on its own.
     */
    private val cookieJar by lazy { CookieJarImpl(context) }

    /** Drops all stored cookies. Called when a session ends. */
    fun clearCookies() {
        cookieJar.clear()
    }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(sessionInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    lateinit var apiService: ApiService
        private set
    
    val osrmApiService: OSRMApiService by lazy {
        Retrofit.Builder()
            .baseUrl(OSRM_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(OSRMApiService::class.java)
    }
    
    /**
     * Nominatim's public instance requires a User-Agent that identifies the
     * application, and rejects or rate-limits generic ones — OkHttp otherwise
     * sends "okhttp/4.12.0". See operations.osmfoundation.org/policies/nominatim.
     *
     * A separate client rather than an extra interceptor on the shared one, so
     * this header is not attached to requests to our own backend or to Google.
     */
    private val nominatimClient by lazy {
        okHttpClient.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "AfamFresh/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID})"
                        )
                        .build()
                )
            }
            .build()
    }

    val nominatimApiService: NominatimApiService by lazy {
        Retrofit.Builder()
            .baseUrl(NOMINATIM_BASE_URL)
            .client(nominatimClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(NominatimApiService::class.java)
    }

    val googleGeocodingApi: GoogleGeocodingApi by lazy {
        Retrofit.Builder()
            .baseUrl(GOOGLE_GEOCODING_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(GoogleGeocodingApi::class.java)
    }

    fun initialize(context: Context) {
        this.context = context
        // Both are used from the interceptor above and from error
        // classification, so they must be ready before any request runs.
        SessionTracker.initialize(context)
        NetworkStatus.initialize(context)
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        apiService = retrofit.create(ApiService::class.java)
    }
}