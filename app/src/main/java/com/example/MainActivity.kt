package com.example

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
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
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * High-performance, production-ready MainActivity hosting an optimized modern WebView.
 *
 * Core Features:
 * - High-speed hardware-accelerated rendering with WebGL and HTML5 Canvas support
 * - Fast loading with pre-caching, smart cache policies, and initial splash transition
 * - Smooth animated progress indicators
 * - Wikimedia Commons and dynamic map photo CORS & disk cache proxy
 * - Seamless native Google Maps app integration
 * - Automatic PWA "Install on phone" prompt suppression
 * - Clean edge-to-edge support with no pull-to-refresh jitter
 * - Fully compatible from Android 7.0 (API 24) to the latest Android 16+ releases
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var splashLoader: FrameLayout
    private lateinit var errorLayout: LinearLayout
    private lateinit var btnRetry: Button

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null
    private var isFirstLoadComplete = false

    // High-performance OkHttp client for caching images and map tiles
    private val okHttpClient by lazy {
        val cacheDir = File(cacheDir, "web_media_cache")
        val cache = Cache(cacheDir, 64L * 1024 * 1024) // 64 MB disk cache
        OkHttpClient.Builder()
            .cache(cache)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    companion object {
        const val TARGET_URL = "https://mehedi-hasan.ai.studio/"

        // JavaScript enhancement snippet:
        // 1. Removes PWA install prompts and floating badges
        // 2. Normalizes crossOrigin attributes on images
        // 3. Normalizes Google Maps links so they open cleanly in the native Google Maps app
        private const val SCRIPT_ENHANCEMENT = """
            (function() {
                try {
                    Object.defineProperty(window.navigator, 'standalone', { get: () => true, configurable: true });
                } catch(e) {}

                function enhancePage() {
                    // A. Purge Install prompt elements
                    const elements = document.querySelectorAll('button, div, span, a');
                    elements.forEach(function(el) {
                        const txt = (el.textContent || '').trim();
                        if (txt.includes('ফোনে ইনস্টল') || 
                            txt.includes('অ্যাপ যুক্ত করুন') || 
                            txt.includes('ইনস্টল করুন') || 
                            txt.includes('অ্যাপ ইনস্টলড') ||
                            txt.includes('অ্যান্ড্রয়েড ফোনে ইনস্টল')) {
                            
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

                    // B. Normalize crossOrigin attributes on image tags
                    document.querySelectorAll('img[crossorigin]').forEach(function(img) {
                        img.removeAttribute('crossorigin');
                    });

                    // C. Normalize Google Maps link targets for direct interception
                    document.querySelectorAll('a[href*="google.com/maps"], a[href*="maps.google"]').forEach(function(a) {
                        a.removeAttribute('target');
                    });
                }

                enhancePage();

                if (!window.__pageEnhanceObserver) {
                    window.__pageEnhanceObserver = new MutationObserver(enhancePage);
                    if (document.documentElement) {
                        window.__pageEnhanceObserver.observe(document.documentElement, { childList: true, subtree: true });
                    }
                }
            })();
        """
    }

    // HTML5 File Chooser contract (<input type="file">)
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback != null) {
            val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    // Geolocation permission launcher for interactive maps
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
            hideSplashLoader()
        } else {
            loadAppUrl(TARGET_URL)
        }
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        splashLoader = findViewById(R.id.splashLoader)
        errorLayout = findViewById(R.id.errorLayout)
        btnRetry = findViewById(R.id.btnRetry)

        // Disable pull-to-refresh and bounce/overscroll effects
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        btnRetry.setOnClickListener {
            errorLayout.visibility = View.GONE
            webView.visibility = View.VISIBLE
            splashLoader.alpha = 1f
            splashLoader.visibility = View.VISIBLE
            isFirstLoadComplete = false

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
        // Modern OnBackPressedCallback compatible with Predictive Back
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
        // 1. Hardware acceleration for smooth 60fps rendering of maps & WebGL
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val settings = webView.settings

        // 2. JavaScript & Storage APIs
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        settings.databaseEnabled = true

        // 3. Intelligent cache: LOAD_DEFAULT when online for freshness, LOAD_CACHE_ELSE_NETWORK when offline
        settings.cacheMode = if (isNetworkConnected()) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        // 4. Performance & smooth rendering
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.blockNetworkLoads = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        // 5. Viewport optimizations
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        // 6. User-Agent normalization: Remove "Version/4.0" to avoid CDN/CORS blocks on images
        val defaultUa = settings.userAgentString
        settings.userAgentString = defaultUa.replace("Version/4.0 ", "").replace("; wv", "")

        // 7. Cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // 8. Modern WebViewClient
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return handleUrlNavigation(view, uri)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri = request?.url ?: return null
                val host = uri.host?.lowercase() ?: ""
                val path = uri.path?.lowercase() ?: ""

                // Intercept Wikimedia Commons images and dynamic photo requests to guarantee CORS compliance and disk caching
                if (host.contains("upload.wikimedia.org") ||
                    path.endsWith(".jpg") ||
                    path.endsWith(".jpeg") ||
                    path.endsWith(".png") ||
                    path.endsWith(".webp")
                ) {
                    val interceptedResponse = fetchAndCacheImage(request)
                    if (interceptedResponse != null) {
                        return interceptedResponse
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 5
                injectEnhancementScript(view)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                hideSplashLoader()
                injectEnhancementScript(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                hideSplashLoader()
                injectEnhancementScript(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    hideSplashLoader()

                    if (!isNetworkConnected() && (view?.url == null || view.url == "about:blank")) {
                        webView.visibility = View.GONE
                        errorLayout.visibility = View.VISIBLE
                    }
                }
            }

            // Protect against WebView renderer process termination by Android OS
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                view?.destroy()
                recreate()
                return true
            }
        }

        // 9. Modern WebChromeClient
        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)

                // Smooth progress animation
                val animator = ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, newProgress)
                animator.duration = 180
                animator.interpolator = DecelerateInterpolator()
                animator.start()

                if (newProgress >= 70) {
                    hideSplashLoader()
                }

                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else if (progressBar.visibility == View.GONE) {
                    progressBar.visibility = View.VISIBLE
                }

                if (newProgress in 20..95) {
                    injectEnhancementScript(view)
                }
            }

            // Handle window.open & target="_blank" links (Google Maps buttons)
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val hitTest = view?.hitTestResult
                val extra = hitTest?.extra
                if (!extra.isNullOrEmpty()) {
                    val uri = Uri.parse(extra)
                    handleUrlNavigation(view, uri)
                    return false
                }

                val tempWebView = WebView(this@MainActivity).apply {
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                            val targetUri = request?.url ?: return false
                            handleUrlNavigation(view, targetUri)
                            return true
                        }
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }

            // Geolocation permissions for dynamic maps
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

            // HTML5 File Chooser
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
                    Toast.makeText(this@MainActivity, "ফাইল খোলার কোনো অ্যাপ পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }

    private fun hideSplashLoader() {
        if (!isFirstLoadComplete && splashLoader.visibility == View.VISIBLE) {
            isFirstLoadComplete = true
            splashLoader.animate()
                .alpha(0f)
                .setDuration(220)
                .withEndAction {
                    splashLoader.visibility = View.GONE
                }
                .start()
        }
    }

    /**
     * Intercepts photo and map image requests to supply explicit CORS headers and persist to OkHttp disk cache.
     */
    private fun fetchAndCacheImage(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        return try {
            val okHttpRequest = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build()

            val response = okHttpClient.newCall(okHttpRequest).execute()
            if (!response.isSuccessful) {
                return null
            }
            val body = response.body ?: return null
            val rawContentType = response.header("Content-Type", "image/jpeg") ?: "image/jpeg"
            val mimeType = rawContentType.substringBefore(";").trim()

            val responseHeaders = mutableMapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to "*",
                "Cache-Control" to "public, max-age=2592000, immutable"
            )

            WebResourceResponse(
                mimeType,
                response.header("Content-Encoding", "identity"),
                response.code,
                response.message.ifEmpty { "OK" },
                responseHeaders,
                body.byteStream()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Handles navigation, opening Google Maps and external applications cleanly.
     */
    private fun handleUrlNavigation(view: WebView?, uri: Uri): Boolean {
        val urlStr = uri.toString()
        val scheme = uri.scheme?.lowercase() ?: ""
        val host = uri.host?.lowercase() ?: ""
        val path = uri.path?.lowercase() ?: ""

        // 1. Google Maps URLs & Location schemes
        if (host.contains("maps.google") ||
            (host.contains("google.") && path.contains("maps")) ||
            host.contains("goo.gl") ||
            urlStr.contains("google.com/maps") ||
            scheme == "geo"
        ) {
            openInGoogleMapsOrBrowser(uri)
            return true
        }

        // 2. External app schemes (tel, mailto, sms, whatsapp, etc.)
        if (scheme != "http" && scheme != "https") {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
                true
            } catch (e: Exception) {
                Toast.makeText(this, "অ্যাপটি খোলার কোনো মাধ্যম পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
                true
            }
        }

        // 3. External HTTP/HTTPS domains (e.g. Wikipedia, Wikimedia) -> open externally
        if (!host.contains("mehedi-hasan.ai.studio")) {
            openInGoogleMapsOrBrowser(uri)
            return true
        }

        // 4. Internal application navigation stays in WebView
        return false
    }

    /**
     * Opens Google Maps in the native Google Maps app, with browser fallback.
     */
    private fun openInGoogleMapsOrBrowser(uri: Uri) {
        val urlStr = uri.toString()
        if (urlStr.contains("google.com/maps") || urlStr.contains("maps.google") || uri.scheme == "geo") {
            try {
                val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                }
                startActivity(mapIntent)
                return
            } catch (e: Exception) {
                // Google Maps app not available, fallback to browser
            }
        }

        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(browserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "লিঙ্কটি খোলা সম্ভব হচ্ছে না", Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectEnhancementScript(view: WebView?) {
        view?.evaluateJavascript(SCRIPT_ENHANCEMENT, null)
    }

    private fun loadAppUrl(url: String) {
        webView.settings.cacheMode = if (isNetworkConnected()) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
        webView.loadUrl(url)
    }

    /**
     * Modern network connectivity check using ConnectivityManager and NetworkCapabilities.
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
