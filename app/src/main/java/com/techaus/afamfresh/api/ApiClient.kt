package com.techaus.afamfresh.api

import android.content.Context
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    // ===== PRODUCTION (Live Server) =====
    // private const val BASE_URL = "https://afam.techaus.online/api/"
    // private const val OSRM_BASE_URL = "https://router.project-osrm.org/:5000/"
    // private const val NOMINATIM_BASE_URL = "https://afam.techaus.online:8080/"
    
    // ===== PHYSICAL PHONE (Same Wi-Fi as PC) =====
     private const val BASE_URL = "http://192.168.3.41/api/"
     private const val OSRM_BASE_URL = "https://router.project-osrm.org/:5000/"
     private const val NOMINATIM_BASE_URL = "http://192.168.3.41:8080/"
    
    // ===== EMULATOR (Android Virtual Device) =====
    // private const val BASE_URL = "http://10.0.2.2/api/"
    // private const val OSRM_BASE_URL = "http://10.0.2.2:5000/"
    // private const val NOMINATIM_BASE_URL = "http://10.0.2.2:8080/"
    
    // Google Geocoding
    private const val GOOGLE_GEOCODING_BASE_URL = "https://maps.googleapis.com/"
    
    // 🔑 Replace with your actual API key
    const val GOOGLE_MAPS_API_KEY = "AIzaSyBmz6dGnddW1BLyebi3bOQcyeksfxZgiUI"
    
    private val gson = GsonBuilder()
        .setLenient()
        .create()
    
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    private lateinit var context: Context
    
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJarImpl(context))
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
    
    val nominatimApiService: NominatimApiService by lazy {
        Retrofit.Builder()
            .baseUrl(NOMINATIM_BASE_URL)
            .client(okHttpClient)
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
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        apiService = retrofit.create(ApiService::class.java)
    }
}