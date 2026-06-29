/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import at.bitfire.davdroid.R
import com.mudita.frontitude.R as RFrontitude
import at.bitfire.davdroid.ui.KompaktTypography900
import at.bitfire.davdroid.ui.composable.KompaktTheme
import at.bitfire.davdroid.ui.composable.KompaktTopAppBar
import com.mudita.mmd.components.text.TextMMD
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse

/**
 * Runs the OAuth authorization step in an **embedded WebView** instead of Chrome Custom Tabs.
 *
 * The Mudita e-ink device has no Chrome / Custom Tabs, so we host the provider's authorization
 * page ourselves and intercept the redirect to [AuthorizationRequest.redirectUri] to obtain the
 * authorization code. Everything else (PKCE request building, token exchange in
 * [at.bitfire.davdroid.network.OAuthIntegration.authenticate], [net.openid.appauth.AuthState]
 * storage) is unchanged.
 *
 * Google blocks embedded WebViews for OAuth (`disallowed_useragent`). The workaround is to take the
 * device's real WebView User-Agent and strip its web-view markers so it looks like plain Chrome —
 * see [chromeUserAgent].
 */
class KompaktOAuthWebViewActivity : ComponentActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authUrl = intent.getStringExtra(EXTRA_AUTH_URL)
        val redirectUri = intent.getStringExtra(EXTRA_REDIRECT_URI)
        val requestJson = intent.getStringExtra(EXTRA_REQUEST_JSON)
        if (authUrl == null || redirectUri == null || requestJson == null) {
            // missing input → treat as cancelled
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val request = try {
            AuthorizationRequest.jsonDeserialize(requestJson)
        } catch (_: Exception) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // back: navigate within the auth pages first; only finish (= cancel) at the start
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack())
                    wv.goBack()
                else {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        })

        setContent {
            KompaktTheme {
                Scaffold(
                    topBar = {
                        KompaktTopAppBar(
                            title = {
                                TextMMD(
                                    text = stringResource(RFrontitude.string.calendar_accountsync_dialog_button_linkaccount),
                                    style = KompaktTypography900.titleMedium
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { onBackPressedDispatcher.onBackPressed() }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_kompakt_arrow_left),
                                        contentDescription = stringResource(R.string.navigate_up)
                                    )
                                }
                            }
                        )
                    }
                ) { padding ->
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        factory = { context ->
                            createWebView(context, request, redirectUri).also { view ->
                                webView = view
                                view.loadUrl(authUrl)
                            }
                        }
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, request: AuthorizationRequest, redirectUri: String): WebView {
        val view = WebView(context)
        view.settings.apply {
            userAgentString = chromeUserAgent(context)
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val url = req.url
                if (url.toString().startsWith(redirectUri)) {
                    handleRedirect(request, url)
                    return true
                }
                return false
            }
        }
        return view
    }

    /**
     * Builds the User-Agent for the OAuth WebView from the device's **real** system WebView UA,
     * stripping the web-view markers (`Build/…`, `; wv`, `Version/4.0`) so it looks like plain
     * Chrome. This keeps Google from rejecting the embedded WebView (no `wv` marker) while the
     * "new sign-in" security e-mail shows the actual device instead of a fake one.
     */
    private fun chromeUserAgent(context: Context): String =
        WebSettings.getDefaultUserAgent(context)
            .replace(Regex("""\s*Build/[^;)]+"""), "")      // drop build id
            .replace("; wv", "")                             // drop web-view marker
            .replace(Regex("""Version/\d+\.\d+\s*"""), "")   // drop the Version/4.0 token

    private fun handleRedirect(request: AuthorizationRequest, uri: Uri) {
        // error response (e.g. user denied access)
        if (uri.getQueryParameter("error") != null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val response = AuthorizationResponse.Builder(request)
            .fromUri(uri)
            .build()

        // reject mismatched state (CSRF protection)
        if (response.state != request.state) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESPONSE_JSON, response.jsonSerializeString()))
        finish()
    }


    /**
     * Drop-in replacement for `OAuthIntegration.AuthorizationContract`: same input/output, but the
     * authorization happens in [KompaktOAuthWebViewActivity] rather than Custom Tabs.
     */
    class Contract : ActivityResultContract<AuthorizationRequest, AuthorizationResponse?>() {
        override fun createIntent(context: Context, input: AuthorizationRequest) =
            Intent(context, KompaktOAuthWebViewActivity::class.java)
                .putExtra(EXTRA_AUTH_URL, input.toUri().toString())
                .putExtra(EXTRA_REDIRECT_URI, input.redirectUri.toString())
                .putExtra(EXTRA_REQUEST_JSON, input.jsonSerializeString())

        override fun parseResult(resultCode: Int, intent: Intent?): AuthorizationResponse? =
            intent?.getStringExtra(EXTRA_RESPONSE_JSON)?.let { json ->
                try {
                    AuthorizationResponse.jsonDeserialize(json)
                } catch (_: Exception) {
                    null
                }
            }
    }

    companion object {
        private const val EXTRA_AUTH_URL = "auth_url"
        private const val EXTRA_REDIRECT_URI = "redirect_uri"
        private const val EXTRA_REQUEST_JSON = "request_json"
        private const val EXTRA_RESPONSE_JSON = "response_json"
    }

}
