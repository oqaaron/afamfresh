package com.techaus.afamfresh.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.techaus.afamfresh.ui.theme.Cream
import com.techaus.afamfresh.ui.theme.Forest

// ⚠️ HEAVILY INFERRED. Signature matches MainScreen.kt's
// composable("payment_webview/{paymentUrl}/{transactionId}") call exactly, but
// I have no visibility into how your actual Pesapal integration signals
// success/failure back to the app (empty pesapal-callback.php was shared).
//
// This assumes the classic pattern: after payment, Pesapal redirects the
// WebView to YOUR callback URL (api/pesapal-callback.php) with a query param
// indicating status (e.g. ?status=completed / ?status=failed). Adjust
// CALLBACK_URL_MARKER and the query param name below once pesapal-callback.php
// is actually implemented — right now that file is empty, so this can't be
// verified against real behavior.
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PaymentWebViewScreen(
    paymentUrl: String,
    transactionId: String,
    onBack: () -> Unit,
    onPaymentComplete: (Boolean, String) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }

    // ⚠️ Adjust to match your real callback endpoint / custom scheme.
    val callbackUrlMarker = "pesapal-callback.php"

    Scaffold(containerColor = Cream) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                url: String?
                            ): Boolean {
                                if (url != null && url.contains(callbackUrlMarker)) {
                                    val uri = Uri.parse(url)
                                    val status = uri.getQueryParameter("status")
                                    val txnId = uri.getQueryParameter("transaction_id") ?: transactionId
                                    val success = status.equals("completed", ignoreCase = true) ||
                                        status.equals("success", ignoreCase = true)
                                    onPaymentComplete(success, txnId)
                                    return true // stop the WebView from actually navigating there
                                }
                                return false
                            }
                        }
                        loadUrl(paymentUrl)
                    }
                }
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Forest)
                }
            }
        }
    }
}
