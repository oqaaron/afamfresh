package com.techaus.afamfresh.utils

import android.util.Log
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Bridges a Retrofit [Call] to a `(data, error)` callback where the error is a
 * classified [ApiError] rather than a bare null.
 *
 * This keeps the callback-based architecture the app already uses — no Flow,
 * no Result type — while removing the repeated try/catch/log/return-null block
 * from every repository method, which is where the error detail was being
 * thrown away.
 */
fun <T : Any> Call<T>.enqueueApi(
    tag: String,
    label: String,
    onResult: (body: T?, error: ApiError?) -> Unit
) {
    enqueue(object : Callback<T> {
        override fun onResponse(call: Call<T>, response: Response<T>) {
            if (!response.isSuccessful) {
                val error = ApiError.from(response)
                Log.e(tag, "$label HTTP ${response.code()}")
                onResult(null, error)
                return
            }

            val body = response.body()
            if (body == null) {
                // A 200 with no body means the endpoint returned something we
                // could not parse. Reporting it as "unexpected" is honest;
                // silently treating it as an empty list would hide a real bug.
                Log.e(tag, "$label returned an empty body")
                onResult(null, ApiError.Unexpected("Empty response from server"))
                return
            }

            onResult(body, null)
        }

        override fun onFailure(call: Call<T>, t: Throwable) {
            val error = ApiError.from(t)
            Log.e(tag, "$label failed: ${t.message}", t)
            onResult(null, error)
        }
    })
}
