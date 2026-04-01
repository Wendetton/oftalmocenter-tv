package com.oftalmocenter.tv

import android.annotation.SuppressLint
import android.os.Bundle
import android.speech.tts.TextToSpeech
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

    // Ponte JavaScript → Android TTS nativo
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicia TTS nativo do Android
        tts = TextToSpeech(this, this)

        // Tela cheia real
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        setContentView(R.layout.activity_main)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        webView = findViewById(R.id.webview)

        configureWebView()
        webView.loadUrl(TV_URL)
    }

    // Callback quando TTS inicializa
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Tenta português Brasil, aceita qualquer língua disponível
            val result = tts.setLanguage(Locale("pt", "BR"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED

            if (!ttsReady) {
                // Fallback: tenta português Portugal
                val fallback = tts.setLanguage(Locale("pt", "PT"))
                ttsReady = fallback != TextToSpeech.LANG_MISSING_DATA
                        && fallback != TextToSpeech.LANG_NOT_SUPPORTED
            }

            if (!ttsReady) {
                // Último recurso: idioma padrão do dispositivo
                tts.setLanguage(Locale.getDefault())
                ttsReady = true
            }

            tts.setSpeechRate(0.95f)
            tts.setPitch(1.0f)
        }
    }

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

        // Registra a ponte TTS — acessível via window.AndroidTTS.speak()
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
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    view.postDelayed({ view.reload() }, 5000)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = false
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView.reload()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        webView.destroy()
    }
}
