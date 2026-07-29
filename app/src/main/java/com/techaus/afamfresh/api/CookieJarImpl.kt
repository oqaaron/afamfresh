package com.techaus.afamfresh.api

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

// Referenced by ApiClient.kt (`.cookieJar(CookieJarImpl(context))`) but never
// defined — this is a new file.
//
// Why this matters: your backend is PHP using auth.php with session handling,
// and ApiService.logout() / getCurrentUser() rely on the server recognising the
// session. Without a persistent cookie jar, the PHPSESSID cookie is lost on
// every app restart and the server treats each launch as a new visitor — even
// though AuthRepository still has a token in SharedPreferences. That mismatch
// (client thinks logged in, server disagrees) is a classic source of
// "randomly logged out" bugs.
//
// Cookies are stored in SharedPreferences, serialized one-per-key.
class CookieJarImpl(context: Context) : CookieJar {

    private val prefs = context.getSharedPreferences("cookie_prefs", Context.MODE_PRIVATE)

    // In-memory mirror for fast reads within a session.
    private val cache = mutableMapOf<String, MutableList<Cookie>>()

    init {
        loadFromDisk()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val existing = cache.getOrPut(host) { mutableListOf() }

        cookies.forEach { newCookie ->
            // Replace any cookie with the same name rather than accumulating duplicates.
            existing.removeAll { it.name == newCookie.name }
            if (newCookie.expiresAt > System.currentTimeMillis()) {
                existing.add(newCookie)
            }
        }

        persist(host, existing)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val cookies = cache[host] ?: return emptyList()

        // Drop anything that expired since it was stored.
        val now = System.currentTimeMillis()
        val valid = cookies.filter { it.expiresAt > now }
        if (valid.size != cookies.size) {
            cache[host] = valid.toMutableList()
            persist(host, valid)
        }
        return valid
    }

    fun clear() {
        cache.clear()
        prefs.edit().clear().apply()
    }

    // ===== persistence =====

    private fun persist(host: String, cookies: List<Cookie>) {
        val serialized = cookies.map { it.toString() }.toSet()
        prefs.edit().putStringSet(host, serialized).apply()
    }

    private fun loadFromDisk() {
        prefs.all.forEach { (host, value) ->
            @Suppress("UNCHECKED_CAST")
            val raw = value as? Set<String> ?: return@forEach
            val url = HttpUrl.Builder().scheme("http").host(host).build()
            val cookies = raw.mapNotNull { Cookie.parse(url, it) }.toMutableList()
            if (cookies.isNotEmpty()) cache[host] = cookies
        }
    }
}
