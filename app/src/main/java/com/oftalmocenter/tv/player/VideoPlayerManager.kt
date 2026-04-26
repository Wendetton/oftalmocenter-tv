package com.oftalmocenter.tv.player

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DecoderReuseEvaluation

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

    fun playStream(url: String) {
        Log.i(TAG, "playStream: $url")
        val item = MediaItem.fromUri(url)
        player.setMediaItem(item)
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
        }
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
}
