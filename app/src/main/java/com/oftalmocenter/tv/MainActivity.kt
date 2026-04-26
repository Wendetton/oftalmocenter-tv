package com.oftalmocenter.tv

import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.oftalmocenter.tv.player.StreamSource
import com.oftalmocenter.tv.player.VideoPlayerManager
import com.oftalmocenter.tv.player.YouTubeExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MainActivity — Fase 2 (YouTube nativo via NewPipe Extractor).
 *
 * Pega uma URL do YouTube, extrai a URL real de stream em background,
 * entrega ao ExoPlayer e agenda re-extração preventiva. Em caso de erro
 * de fonte (URL expirada, 403), re-extrai com backoff exponencial.
 *
 * Sem WebView, sem Firestore, sem TTS. Esses entram em fases seguintes.
 *
 * Mantido da Fase 1: WakeLock FULL_WAKE_LOCK, fullscreen/imersivo,
 * BootReceiver inalterado.
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        // URL do YouTube de teste (Fase 2). Será trocada pelo Firestore na Fase 3.
        private const val YOUTUBE_URL = "https://www.youtube.com/watch?v=PRAGLqfNK1o"

        // Re-extração preventiva: streams do YouTube costumam expirar entre 4-6h;
        // 3h dá uma boa margem para refresh transparente antes do 403.
        private const val REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1000L

        // Backoff de retry após erro: 5s, 10s, 20s, 40s, 60s, 60s, ...
        private val BACKOFF_STEPS_MS = longArrayOf(5_000, 10_000, 20_000, 40_000, 60_000)
    }

    private lateinit var playerView: PlayerView
    private lateinit var videoPlayerManager: VideoPlayerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentJob: Job? = null
    private var refreshJob: Job? = null
    private var consecutiveFailures = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyFullscreenFlags()
        acquireWakeLock()

        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player_view)

        videoPlayerManager = VideoPlayerManager(this)
        videoPlayerManager.onSourceError = { error ->
            Log.w(TAG, "source error → re-extraindo. code=${error.errorCodeName}")
            loadYouTube(YOUTUBE_URL, isRetry = true)
        }
        playerView.player = videoPlayerManager.player
        playerView.useController = false

        loadYouTube(YOUTUBE_URL)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFlags()
        acquireWakeLock()
        videoPlayerManager.player.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        // Em kiosk não esperamos pause; mantemos player ativo.
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        currentJob?.cancel()
        refreshJob?.cancel()
        playerView.player = null
        videoPlayerManager.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Log.i(TAG, "BACK pressed → re-extrair YouTube")
            loadYouTube(YOUTUBE_URL)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Extrai e reproduz a URL do YouTube. Cancela qualquer extração em
     * andamento. Em caso de erro, agenda retry com backoff. Em caso de
     * sucesso, agenda re-extração preventiva em 3h.
     */
    private fun loadYouTube(youtubeUrl: String, isRetry: Boolean = false) {
        currentJob?.cancel()
        refreshJob?.cancel()

        currentJob = lifecycleScope.launch {
            try {
                val source: StreamSource = YouTubeExtractor.extract(youtubeUrl)
                consecutiveFailures = 0
                videoPlayerManager.playStream(source)
                scheduleRefresh(youtubeUrl)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                consecutiveFailures += 1
                val backoffMs = BACKOFF_STEPS_MS[
                    (consecutiveFailures - 1).coerceIn(0, BACKOFF_STEPS_MS.size - 1)
                ]
                Log.e(
                    TAG,
                    "extração falhou (#$consecutiveFailures) — retry em ${backoffMs}ms: ${t.message}",
                    t
                )
                delay(backoffMs)
                loadYouTube(youtubeUrl, isRetry = true)
            }
        }
    }

    private fun scheduleRefresh(youtubeUrl: String) {
        refreshJob = lifecycleScope.launch {
            delay(REFRESH_INTERVAL_MS)
            Log.i(TAG, "refresh preventivo (3h) → re-extraindo")
            loadYouTube(youtubeUrl)
        }
    }

    private fun applyFullscreenFlags() {
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
        applyImmersiveFlags()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveFlags() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
                "OftalmoCenterTV::KeepAlive"
            )
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
            it.acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
