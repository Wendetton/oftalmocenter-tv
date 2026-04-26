package com.oftalmocenter.tv.player

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * Encapsula o ciclo de vida de um único ExoPlayer reaproveitável.
 *
 * Fase 1: reprodução simples de uma URL em loop, com logs do decoder
 * efetivamente selecionado para confirmar uso de hardware decoder no Fire TV.
 */
@androidx.media3.common.util.UnstableApi
class VideoPlayerManager(private val context: Context) {

    companion object {
        private const val TAG = "VideoPlayerManager"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; AFTSS Build/PS7233) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    /**
     * Callback chamado quando o player reporta erro provavelmente associado
     * à fonte (URL expirada, 403, parsing). A camada acima decide se
     * re-extrai do YouTube.
     */
    var onSourceError: ((PlaybackException) -> Unit)? = null

    // Os listeners precisam ser inicializados ANTES da propriedade `player`
    // porque o bloco `apply { addListener(...) }` é executado durante a
    // construção e o Kotlin inicializa as propriedades na ordem do arquivo.
    private val playbackListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val name = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "?"
            }
            Log.i(TAG, "state -> $name")
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "player error: ${error.errorCodeName} | ${error.message}", error)
            if (isSourceLikelyError(error)) {
                onSourceError?.invoke(error)
            }
        }
    }

    private fun isSourceLikelyError(error: PlaybackException): Boolean {
        return error.errorCode in setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
        )
    }

    private val decoderListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            val isSoftware = decoderName.startsWith("OMX.google.", ignoreCase = true) ||
                decoderName.startsWith("c2.android.", ignoreCase = true)
            if (isSoftware) {
                Log.w(TAG, "VIDEO decoder = $decoderName  (SOFTWARE FALLBACK!)")
            } else {
                Log.i(TAG, "VIDEO decoder = $decoderName  (hardware ok)")
            }
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            Log.i(TAG, "AUDIO decoder = $decoderName")
        }

        override fun onVideoDecoderReleased(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String
        ) {
            Log.i(TAG, "VIDEO decoder released = $decoderName")
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            Log.i(
                TAG,
                "VIDEO format = ${format.sampleMimeType} ${format.width}x${format.height}@${format.frameRate}fps"
            )
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        playWhenReady = true
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        addListener(playbackListener)
        addAnalyticsListener(decoderListener)
    }

    /** Reproduz uma URL HTTP simples (mp4 progressivo). */
    fun playStream(url: String) {
        Log.i(TAG, "playStream: $url")
        val item = MediaItem.fromUri(url)
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Reproduz um [StreamSource]. Se for adaptive (vídeo+áudio separados),
     * combina via MergingMediaSource — única forma do ExoPlayer tocar os
     * dois streams sincronizados como um único MediaItem.
     */
    fun playStream(source: StreamSource) {
        Log.i(
            TAG,
            "playStream: '${source.title}' ${source.resolution} " +
                if (source.isAdaptive) "(adaptive)" else "(progressive)"
        )

        if (!source.isAdaptive) {
            playStream(source.videoUrl)
            return
        }

        // Adaptive: vídeo-only + áudio-only -> MergingMediaSource.
        // User-Agent customizado evita 403 raros que o YouTube manda para
        // requests sem identificação típica de browser.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)

        val videoMs = ProgressiveMediaSource.Factory(httpFactory)
            .createMediaSource(MediaItem.fromUri(source.videoUrl))
        val audioMs = ProgressiveMediaSource.Factory(httpFactory)
            .createMediaSource(MediaItem.fromUri(source.audioUrl!!))

        player.setMediaSource(MergingMediaSource(videoMs, audioMs))
        player.prepare()
        player.playWhenReady = true
    }


    fun setVolume(percent: Int) {
        val v = (percent.coerceIn(0, 100)) / 100f
        player.volume = v
    }

    fun release() {
        try {
            player.removeListener(playbackListener)
            player.removeAnalyticsListener(decoderListener)
            player.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release error: ${t.message}")
        }
    }
}
