package com.oftalmocenter.tv.player

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extrai uma URL direta de stream a partir de uma URL pública do YouTube,
 * via NewPipe Extractor. Bloqueante — chamar dentro de Dispatchers.IO.
 *
 * Estratégia de seleção (ajustada para Fire TV Stick HD):
 *   1. Stream **progressivo** (vídeo+áudio juntos) em mp4 480p.
 *   2. Progressivo mp4 360p (último recurso "1 stream só").
 *   3. **Adaptive** (vídeo e áudio separados) — vídeo 720p mp4 + melhor áudio.
 *
 * Em 2024+ o YouTube praticamente parou de servir progressivos acima de
 * 360p, então o caminho 3 é o que costuma ser usado para qualidade decente.
 */
object YouTubeExtractor {

    private const val TAG = "YouTubeExtractor"
    private val initialized = AtomicBoolean(false)

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(NewPipeDownloader)
            Log.i(TAG, "NewPipe inicializado")
        }
    }

    suspend fun extract(youtubeUrl: String): StreamSource = withContext(Dispatchers.IO) {
        ensureInitialized()
        val service = ServiceList.YouTube
        Log.i(TAG, "extraindo: $youtubeUrl")

        val info: StreamInfo = StreamInfo.getInfo(service, youtubeUrl)
        Log.i(TAG, "  título: '${info.name}'  duração: ${info.duration}s")

        // 1) Tentar progressivo mp4 (vídeo+áudio juntos).
        val progressives = info.videoStreams
            .filter { it.isVideoOnly().not() }
            .filter { it.format?.mimeType?.contains("mp4", ignoreCase = true) == true }
            .filter { it.content.isNotBlank() }

        val progressivePref = pickByResolution(progressives, listOf("480p", "360p", "720p", "240p"))
        if (progressivePref != null) {
            Log.i(TAG, "  → progressivo ${progressivePref.resolution} mp4")
            return@withContext StreamSource(
                videoUrl = progressivePref.content,
                audioUrl = null,
                title = info.name ?: "",
                durationSeconds = info.duration,
                resolution = progressivePref.resolution ?: "?"
            )
        }

        // 2) Fallback adaptive: vídeo-only mp4 (h264) + áudio-only m4a.
        val videoOnly = info.videoOnlyStreams
            .filter { it.format?.mimeType?.contains("mp4", ignoreCase = true) == true }
            .filter { it.content.isNotBlank() }
        val videoPref = pickByResolution(videoOnly, listOf("720p", "480p", "360p", "1080p"))
            ?: error("Nenhum stream de vídeo (mp4) disponível para $youtubeUrl")

        val audioPref = info.audioStreams
            .filter { it.format?.mimeType?.contains("mp4", ignoreCase = true) == true ||
                      it.format?.mimeType?.contains("audio", ignoreCase = true) == true }
            .filter { it.content.isNotBlank() }
            .maxByOrNull { it.averageBitrate }
            ?: error("Nenhum stream de áudio disponível para $youtubeUrl")

        Log.i(TAG, "  → adaptive vídeo ${videoPref.resolution} + áudio ${audioPref.averageBitrate}bps")
        StreamSource(
            videoUrl = videoPref.content,
            audioUrl = audioPref.content,
            title = info.name ?: "",
            durationSeconds = info.duration,
            resolution = videoPref.resolution ?: "?"
        )
    }

    private fun pickByResolution(
        streams: List<VideoStream>,
        preferenceOrder: List<String>
    ): VideoStream? {
        for (res in preferenceOrder) {
            val match = streams.firstOrNull { it.resolution.equals(res, ignoreCase = true) }
            if (match != null) return match
        }
        return streams.firstOrNull()
    }
}
