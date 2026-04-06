package com.oftalmocenter.tv

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

// ⚙️ CONFIGURAÇÃO — altere aqui a URL do seu sistema
private const val TV_URL = "https://webtv-chi.vercel.app/tv"

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ===== TTS Bridge (JavaScript ↔ Android) =====
    inner class TTSBridge {
        @JavascriptInterface
        fun speak(text: String) {
            if (!ttsReady || text.isBlank()) return
            tts.stop()
            tts.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null, "announce")
        }

        @JavascriptInterface
        fun isReady(): Boolean = ttsReady
    }

    // ===== LIFECYCLE =====

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        // Flags de tela: mantém ligada, fullscreen, imersivo
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            // Impede que o Fire TV mostre screensaver sobre o app
            addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        // WakeLock: impede que a CPU entre em deep sleep
        acquireWakeLock()

        setContentView(R.layout.activity_main)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        webView = findViewById(R.id.webview)

        configureWebView()
        webView.loadUrl(TV_URL)
        registerConnectivityMonitor()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("pt", "BR"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ttsReady) {
                val fallback = tts.setLanguage(Locale("pt", "PT"))
                ttsReady = fallback != TextToSpeech.LANG_MISSING_DATA
                        && fallback != TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (!ttsReady) {
                tts.setLanguage(Locale.getDefault())
                ttsReady = true
            }
            tts.setSpeechRate(0.95f)
            tts.setPitch(1.0f)

            // Notifica o JavaScript quando o TTS termina de falar
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        webView.evaluateJavascript(
                            "if(typeof window.tvTTSDone==='function') window.tvTTSDone()",
                            null
                        )
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        webView.evaluateJavascript(
                            "if(typeof window.tvTTSDone==='function') window.tvTTSDone()",
                            null
                        )
                    }
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        // Reforça fullscreen imersivo sempre que voltar ao app
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        // Readquire WakeLock caso tenha sido liberado
        acquireWakeLock()
    }

    override fun onPause() {
        super.onPause()
        // Intencionalmente NÃO pausamos o WebView
        // Mantém o YouTube e Firebase listener ativos em background
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        connectivityCallback?.let {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(it)
        }
        connectivityCallback = null
        tts.stop()
        tts.shutdown()
        webView.destroy()
    }

    // ===== WAKELOCK =====

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
            "OftalmoCenterTV::KeepAlive"
        )
        wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 horas — renovado no onResume
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    // ===== WEBVIEW =====

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.addJavascriptInterface(TTSBridge(), "AndroidTTS")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView?.let { onHideCustomView(); return }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let { fullscreenContainer.removeView(it); customView = null }
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            private var reloadScheduled = false

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame && !reloadScheduled) {
                    reloadScheduled = true
                    view.postDelayed({
                        reloadScheduled = false
                        view.reload()
                    }, 15000)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                reloadScheduled = false
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = false
        }

        WebView.setWebContentsDebuggingEnabled(false)
    }

    // ===== CONECTIVIDADE =====

    private fun registerConnectivityMonitor() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
            private var reloadPending = false

            override fun onAvailable(network: Network) {
                mainHandler.post {
                    webView.evaluateJavascript(
                        "if(typeof window.tvSetOffline==='function') window.tvSetOffline(false)",
                        null
                    )
                    if (reloadPending) {
                        reloadPending = false
                        mainHandler.postDelayed({
                            webView.evaluateJavascript(
                                "(function(){ try { if(window.tvNeedsReload) { window.tvNeedsReload=false; location.reload(); } } catch(e){} })()",
                                null
                            )
                        }, 5000)
                    }
                }
            }

            override fun onLost(network: Network) {
                reloadPending = true
                mainHandler.post {
                    webView.evaluateJavascript(
                        "if(typeof window.tvSetOffline==='function') window.tvSetOffline(true)",
                        null
                    )
                }
            }
        }

        cm.registerNetworkCallback(request, connectivityCallback!!)
    }

    // ===== CONTROLE REMOTO =====
    // Apenas o Back recarrega a página. Todo o resto funciona normal.

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView.reload()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
