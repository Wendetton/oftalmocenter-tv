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
import com.oftalmocenter.tv.cache.CacheManager
import com.oftalmocenter.tv.firestore.FirestorePoller
import com.oftalmocenter.tv.player.StreamSource
import com.oftalmocenter.tv.player.VideoPlayerManager
import com.oftalmocenter.tv.player.YouTubeExtractor
import com.oftalmocenter.tv.ui.PatientCallOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MainActivity — Fase 3 (Firestore + cache + controle remoto de vídeo).
 *
 * O `videoId` e o `ytVolume` agora vêm do Firestore (`config/main` e
 * `config/control`), em tempo real. Mudanças no painel admin web
 * refletem na TV em segundos. Offline / sem internet, o último estado
 * conhecido é restaurado do cache local.
 *
 * Mantido das fases anteriores: WakeLock, fullscreen/imersivo,
 * BootReceiver, ExoPlayer com hardware decoder, NewPipe Extractor,
 * re-extração preventiva e por erro de fonte.
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        // Projeto Firestore consumido (mesmo do app web). API key fica no APK
        // — sem valor de segurança, o controle de acesso vive nas Security
        // Rules do Firestore.
        private const val FIRESTORE_PROJECT_ID = "webtv-ee904"
        private const val FIRESTORE_API_KEY = "AIzaSyB-04iQ91vSQjZJaRAxyCX2Fcq-vDGHa0o"

        // Re-extração preventiva: streams do YouTube costumam expirar entre 4-6h;
        // 3h dá uma boa margem para refresh transparente antes do 403.
        private const val REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1000L

        // Backoff de retry após erro: 5s, 10s, 20s, 40s, 60s, 60s, ...
        private val BACKOFF_STEPS_MS = longArrayOf(5_000, 10_000, 20_000, 40_000, 60_000)

        private fun youtubeUrlFromId(id: String) = "https://www.youtube.com/watch?v=$id"
    }

    private lateinit var playerView: PlayerView
    private lateinit var videoPlayerManager: VideoPlayerManager
    private lateinit var cache: CacheManager
    private lateinit var firestorePoller: FirestorePoller
    private lateinit var callOverlay: PatientCallOverlay

    private var wakeLock: PowerManager.WakeLock? = null
    private var currentJob: Job? = null
    private var refreshJob: Job? = null
    private var consecutiveFailures = 0

    /** Último videoId efetivamente carregado no player. Evita reload desnecessário. */
    private var loadedVideoId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyFullscreenFlags()
        acquireWakeLock()

        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player_view)

        callOverlay = PatientCallOverlay(findViewById(R.id.root_container))
        callOverlay.start()

        cache = CacheManager(this)
        videoPlayerManager = VideoPlayerManager(this)
        videoPlayerManager.onSourceError = { error ->
            Log.w(TAG, "source error → re-extraindo. code=${error.errorCodeName}")
            loadedVideoId?.let { loadVideoId(it, isRetry = true) }
        }
        playerView.player = videoPlayerManager.player
        playerView.useController = false

        // 1) Estado inicial vindo do cache (resposta imediata, antes do Firestore).
        cache.loadVideoId()?.let { cachedId ->
            Log.i(TAG, "cache → videoId = $cachedId")
            videoPlayerManager.setVolume(cache.loadVolume())
            loadVideoId(cachedId)
        } ?: run {
            videoPlayerManager.setVolume(cache.loadVolume())
            Log.i(TAG, "sem cache; aguardando Firestore")
        }

        // 2) Firestore poller via REST. Polling 10s para vídeo/volume e 1s
        //    para chamadas (config/announce). Sem SDK Firebase, sem gRPC.
        firestorePoller = FirestorePoller(
            projectId = FIRESTORE_PROJECT_ID,
            apiKey = FIRESTORE_API_KEY,
            onVideoIdChanged = { videoId ->
                if (videoId.isNullOrBlank()) {
                    Log.w(TAG, "videoId vazio recebido do Firestore — ignorando")
                    return@FirestorePoller
                }
                if (videoId == loadedVideoId) {
                    Log.i(TAG, "videoId inalterado ($videoId) — sem reload")
                    return@FirestorePoller
                }
                cache.saveVideoId(videoId)
                loadVideoId(videoId)
            },
            onVolumeChanged = { percent ->
                cache.saveVolume(percent)
                videoPlayerManager.setVolume(percent)
            },
            onCallStateChanged = { idle, nome, sala ->
                callOverlay.applyState(idle, nome, sala)
            }
        )
        firestorePoller.start()
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
        firestorePoller.stop()
        callOverlay.stop()
        currentJob?.cancel()
        refreshJob?.cancel()
        playerView.player = null
        videoPlayerManager.release()
        releaseWakeLock()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Tecla BACK: alterna entre simular uma chamada de teste e esconder.
        // Útil para validar a UI da chamada sem precisar do admin web.
        // Será substituída pela lógica de TTS na Fase 5.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (testCallShowing) {
                Log.i(TAG, "BACK pressed → esconder chamada de teste")
                callOverlay.hideCall()
            } else {
                Log.i(TAG, "BACK pressed → mostrar chamada de teste")
                callOverlay.showCall("FERNANDO AZEVEDO", "Consultório 2")
            }
            testCallShowing = !testCallShowing
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private var testCallShowing = false

    /**
     * Extrai o `videoId` do YouTube e reproduz. Cancela qualquer extração
     * em andamento. Em caso de erro, agenda retry com backoff. Em caso de
     * sucesso, agenda re-extração preventiva em 3h.
     */
    private fun loadVideoId(videoId: String, isRetry: Boolean = false) {
        currentJob?.cancel()
        refreshJob?.cancel()

        val youtubeUrl = youtubeUrlFromId(videoId)
        currentJob = lifecycleScope.launch {
            try {
                val source: StreamSource = YouTubeExtractor.extract(youtubeUrl)
                consecutiveFailures = 0
                loadedVideoId = videoId
                videoPlayerManager.playStream(source)
                scheduleRefresh(videoId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                consecutiveFailures += 1
                val backoffMs = BACKOFF_STEPS_MS[
                    (consecutiveFailures - 1).coerceIn(0, BACKOFF_STEPS_MS.size - 1)
                ]
                Log.e(
                    TAG,
                    "extração falhou (#$consecutiveFailures, videoId=$videoId) — retry em ${backoffMs}ms: ${t.message}",
                    t
                )
                delay(backoffMs)
                loadVideoId(videoId, isRetry = true)
            }
        }
    }

    private fun scheduleRefresh(videoId: String) {
        refreshJob = lifecycleScope.launch {
            delay(REFRESH_INTERVAL_MS)
            Log.i(TAG, "refresh preventivo (3h) → re-extraindo $videoId")
            loadVideoId(videoId)
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
