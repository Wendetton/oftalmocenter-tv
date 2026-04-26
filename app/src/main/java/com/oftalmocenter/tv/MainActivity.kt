package com.oftalmocenter.tv

import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.oftalmocenter.tv.player.VideoPlayerManager

/**
 * MainActivity — Fase 1 (player nativo básico).
 *
 * Reproduz uma URL de teste em mp4 (Big Buck Bunny) em loop usando ExoPlayer
 * com SurfaceView, para validar que o hardware decoder do Fire TV está sendo
 * utilizado. Não há WebView, Firestore, TTS ou overlay nesta fase — esses
 * componentes voltam nas fases seguintes.
 *
 * O que foi preservado da versão anterior:
 *  - WakeLock FULL_WAKE_LOCK (impede o Fire TV de entrar em standby)
 *  - Flags de fullscreen / imersivo
 *  - BootReceiver (autostart) inalterado
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        // URL de teste — Sintel Trailer (sample do W3C, HTTPS, mp4 H.264).
        // Estável e mantido pelo próprio W3C; substitui o Big Buck Bunny do
        // Google que passou a responder HTTP 403. Será trocado por stream
        // do YouTube na Fase 2.
        private const val TEST_VIDEO_URL =
            "https://media.w3.org/2010/05/sintel/trailer.mp4"
    }

    private lateinit var playerView: PlayerView
    private lateinit var videoPlayerManager: VideoPlayerManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyFullscreenFlags()
        acquireWakeLock()

        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player_view)

        videoPlayerManager = VideoPlayerManager(this)
        playerView.player = videoPlayerManager.player
        playerView.useController = false

        videoPlayerManager.playStream(TEST_VIDEO_URL)
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
        playerView.player = null
        videoPlayerManager.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Fase 1: tecla Voltar reinicia o vídeo do começo, útil para teste manual.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Log.i(TAG, "BACK pressed → seek to 0")
            videoPlayerManager.player.seekTo(0)
            return true
        }
        return super.onKeyDown(keyCode, event)
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
