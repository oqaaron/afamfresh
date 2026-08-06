package com.techaus.afamfresh.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.techaus.afamfresh.ui.theme.Cream
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.Ink
import com.techaus.afamfresh.ui.theme.InkMuted

/**
 * Hosts the Pesapal checkout page.
 *
 * ✅ WHAT CHANGED, AND WHY IT MATTERS
 *
 * This screen used to decide whether the payment succeeded by reading
 * `?status=` off the callback URL:
 *
 *     val success = status.equals("completed", true) || status.equals("success", true)
 *
 * Pesapal does not send a `status` query parameter, so that was always false —
 * every successful payment was reported to the app as a failure. Worse, it made
 * the app's view of "paid" depend on a URL, which is attacker-controllable and
 * exactly the flaw that was removed from pesapal-ipn.php.
 *
 * Now this screen reports only one fact: **the customer has left the payment
 * page.** Whether money moved is decided by the server asking Pesapal via
 * GetTransactionStatus — see PaymentViewModel.awaitPaymentOutcome.
 *
 * Pesapal appends OrderTrackingId to the callback URL, which is forwarded when
 * present because it is a more reliable key than our own order id.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PaymentWebViewScreen(
    paymentUrl: String,
    transactionId: String,
    onBack: () -> Unit,
    /**
     * Fired when the checkout flow ends, carrying the tracking id to verify.
     *
     * Deliberately NOT `(Boolean, String)`: this screen cannot know whether the
     * payment succeeded, and a signature that implies otherwise invites the
     * caller to trust it.
     */
    onCheckoutFinished: (trackingId: String) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }

    // Set once, so a callback URL that also fires onPageStarted cannot report
    // twice and trigger two verification polls for one payment.
    var reported by remember { mutableStateOf(false) }

    fun finish(url: String?) {
        if (reported) return
        reported = true
        val fromUrl = url?.let { raw ->
            runCatching { Uri.parse(raw) }.getOrNull()?.let { uri ->
                // Pesapal's own casing first, then the lowercase variants.
                uri.getQueryParameter("OrderTrackingId")
                    ?: uri.getQueryParameter("order_tracking_id")
                    ?: uri.getQueryParameter("orderTrackingId")
            }
        }
        onCheckoutFinished(fromUrl?.takeIf { it.isNotBlank() } ?: transactionId)
    }

    /**
     * Our callback page. Reaching it means the customer finished or abandoned
     * checkout; it says nothing about the result.
     */
    fun isCallback(url: String?): Boolean =
        url != null && url.contains("pesapal-callback.php", ignoreCase = true)

    // Leaving mid-payment still needs verifying: the customer may have completed
    // a mobile-money prompt and then backed out of the WebView.
    BackHandler {
        if (!reported) {
            reported = true
            onCheckoutFinished(transactionId)
        }
        onBack()
    }

    Scaffold(containerColor = Cream) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true

                        webViewClient = object : WebViewClient() {

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                // Caught here as well as in shouldOverrideUrlLoading
                                // because a server-side redirect to the callback
                                // does not always go through the override.
                                if (isCallback(url)) finish(url)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString()
                                if (isCallback(url)) {
                                    finish(url)
                                    return true // don't bother rendering our own page
                                }
                                return false
                            }

                            @Deprecated("Kept for API < 24, which the app still supports at minSdk 24")
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                if (isCallback(url)) {
                                    finish(url)
                                    return true
                                }
                                return false
                            }
                        }

                        loadUrl(paymentUrl)
                    }
                }
            )

            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = Forest)
                    Box(modifier = Modifier.height(16.dp))
                    Text("Opening secure payment…", color = Ink, fontWeight = FontWeight.SemiBold)
                    Box(modifier = Modifier.height(4.dp))
                    Text("Do not close this screen", color = InkMuted, fontSize = 12.sp)
                }
            }
        }
    }
}
