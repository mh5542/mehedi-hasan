package com.example

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * MainActivity hosting a modern, offline-capable WebView.
 *
 * Configured specifically for:
 * - Target URL: https://mehedi-hasan.ai.studio/
 * - Pull-to-refresh removed (clean full-screen native interaction)
 * - PWA "Install on phone" (ফোনে ইনস্টল করুন) prompt removed via dynamic DOM/CSS suppression
 * - Cache mode: WebSettings.LOAD_CACHE_ELSE_NETWORK for offline usability
 * - DOM storage and database storage enabled for dynamic maps and client caching
 * - Backward compatible from Android 7.0 (API 24) to the latest Android releases
 * - Google Play Store compliant security posture and modern BackHandler
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorLayout: LinearLayout
    private lateinit var btnRetry: Button

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null

    companion object {
        const val TARGET_URL = "https://mehedi-hasan.ai.studio/"

        // JavaScript snippet to permanently purge the "Install on phone" (ফোনে ইনস্টল / অ্যাপ যুক্ত করুন) button and dialog
        private const val SCRIPT_PURGE_INSTALL_BUTTON = """
            (function() {
                try {
                    Object.defineProperty(window.navigator, 'standalone', { get: () => true, configurable: true });
                } catch(e) {}

                function purgeInstallElements() {
                    const elements = document.querySelectorAll('button, div, span, a');
                    elements.forEach(function(el) {
                        const txt = (el.textContent || '').trim();
                        if (txt.includes('ফোনে ইনস্টল') || 
                            txt.includes('অ্যাপ যুক্ত করুন') || 
                            txt.includes('ইনস্টল করুন') || 
                            txt.includes('অ্যাপ ইনস্টলড') ||
                            txt.includes('অ্যান্ড্রয়েড ফোনে ইনস্টল')) {
                            
                            // Find nearest button or action container
                            const btn = el.closest('button') || 
                                        (el.classList && el.classList.contains('fixed') ? el : null) || 
                                        (el.parentElement && el.parentElement.tagName === 'BUTTON' ? el.parentElement : null);
                            if (btn) {
                                btn.style.setProperty('display', 'none', 'important');
                            } else if (el.tagName === 'BUTTON' || 
                                      (el.className && typeof el.className === 'string' && (el.className.includes('rounded-xl') || el.className.includes('rounded-full')))) {
                                el.style.setProperty('display', 'none', 'important');
                            }
                        }
                    });
                }

                purgeInstallElements();

                if (!window.__installPurgeObserver) {
                    window.__installPurgeObserver = new MutationObserver(purgeInstallElements);
                    if (document.documentElement) {
                        window.__installPurgeObserver.observe(document.documentElement, { childList: true, subtree: true });
                    }
                }
            })();
        """
    }

    // Modern file chooser contract for HTML5 file inputs (<input type="file">)
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback != null) {
            val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    // Modern runtime permission request launcher for Geolocation in dynamic maps
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val granted = fineGranted || coarseGranted
        pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, granted, false)
        pendingGeolocationCallback = null
        pendingGeolocationOrigin = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        initViews()
        applyWindowInsets()
        setupBackNavigation()
        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadAppUrl(TARGET_URL)
        }
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorLayout = findViewById(R.id.errorLayout)
        btnRetry = findViewById(R.id.btnRetry)

        // Disable bounce/overscroll effect on top/bottom edges
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        btnRetry.setOnClickListener {
            errorLayout.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.settings.cacheMode = if (isNetworkConnected()) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
            webView.loadUrl(TARGET_URL)
        }
    }

    private fun applyWindowInsets() {
        val rootContainer = findViewById<View>(R.id.rootContainer)
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, statusBarInsets.top, 0, navBarInsets.bottom)
            insets
        }
    }

    private fun setupBackNavigation() {
        // Modern OnBackPressedCallback compatible with Predictive Back on Android 13+ (API 33+)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupWebView() {
        val settings = webView.settings

        // 1. JavaScript & Storage APIs (Essential for dynamic web apps & maps)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        settings.databaseEnabled = true

        // 2. Offline usability cache mode
        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK

        // 3. Security best practices for Google Play compliance
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // 4. Viewport & Rendering optimizations
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false

        // 5. Cookie Management
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // 6. Modern WebViewClient
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val uri = request.url

                // Handle external application schemes (tel, mailto, sms, geo, whatsapp, etc.)
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    return try {
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        startActivity(intent)
                        true
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this@MainActivity, "No application found to open this link", Toast.LENGTH_SHORT).show()
                        true
                    } catch (e: Exception) {
                        true
                    }
                }

                // Keep web navigation within the WebView
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                injectPurgeScript(view)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                injectPurgeScript(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                injectPurgeScript(view)

                // Restore cache-first mode after loading finishes
                webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Only show full error layout if the main frame failed and no page is loaded
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE

                    // If offline and cache is empty, display the user-friendly offline screen
                    if (!isNetworkConnected() && (view?.url == null || view.url == "about:blank")) {
                        webView.visibility = View.GONE
                        errorLayout.visibility = View.VISIBLE
                    }
                }
            }
        }

        // 7. Modern WebChromeClient
        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else if (progressBar.visibility == View.GONE) {
                    progressBar.visibility = View.VISIBLE
                }

                // Periodically remove install prompt as DOM elements mount
                if (newProgress in 20..99) {
                    injectPurgeScript(view)
                }
            }

            // Geolocation prompt callback for dynamic maps
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val hasFineLocation = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val hasCoarseLocation = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (hasFineLocation || hasCoarseLocation) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeolocationOrigin = origin
                    pendingGeolocationCallback = callback
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }

            // HTML5 File Chooser support (<input type="file">)
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "Cannot open file chooser", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }

    private fun injectPurgeScript(view: WebView?) {
        view?.evaluateJavascript(SCRIPT_PURGE_INSTALL_BUTTON, null)
    }

    private fun loadAppUrl(url: String) {
        if (!isNetworkConnected()) {
            webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
        webView.loadUrl(url)
    }

    /**
     * Non-deprecated network connectivity check using ConnectivityManager and NetworkCapabilities.
     * Compatible with Android 7.0+ (API 24+) through latest Android versions.
     */
    private fun isNetworkConnected(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
