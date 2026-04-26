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

        // Estratégia (atualizada para qualidade):
        // O YouTube atualmente limita streams progressivos (vídeo+áudio
        // juntos) a 360p. Para qualidade decente em TV, é necessário usar
        // adaptive: vídeo-only mp4 (H.264) + áudio-only AAC, combinados
        // pelo ExoPlayer via MergingMediaSource.
        //
        // Ordem de preferência:
        //   1. Adaptive vídeo 720p mp4 + melhor áudio aac (ideal para
        //      Fire TV Stick HD).
        //   2. Adaptive vídeo 1080p mp4 (alta qualidade; downscale no
        //      hardware, pequeno custo de banda extra).
        //   3. Adaptive vídeo 480p mp4.
        //   4. Adaptive vídeo 360p mp4 (último recurso adaptive).
        //   5. Progressivo mp4 (qualquer resolução, fallback final).

        val videoOnly = info.videoOnlyStreams
            .filter { it.format?.mimeType?.contains("mp4", ignoreCase = true) == true }
            .filter { it.content.isNotBlank() }

        val audioPref = info.audioStreams
            .filter { it.format?.mimeType?.contains("mp4", ignoreCase = true) == true ||
                      it.format?.mimeType?.contains("audio", ignoreCase = true) == true }
            .filter { it.content.isNotBlank() }
            .maxByOrNull { it.averageBitrate }

        val videoPref = pickByResolution(videoOnly, listOf("720p", "1080p", "480p", "360p"))
        if (videoPref != null && audioPref != null) {
            Log.i(
                TAG,
                "  → adaptive vídeo ${videoPref.resolution} mp4 + áudio ${audioPref.averageBitrate}bps"
            )
            return@withContext StreamSource(
                videoUrl = videoPref.content,
                audioUrl = audioPref.content,
                title = info.name ?: "",
                durationSeconds = info.duration,
                resolution = videoPref.resolution ?: "?"
            )
        }

        // Fallback: progressivo mp4 (vídeo+áudio juntos) — provavelmente 360p.
        val progressives = info.videoStreams
            .filter { it.isVideoOnly().not() }
            .filter { it.format?.mimeType?.contains("mp4", ignoreCase = true) == true }
            .filter { it.content.isNotBlank() }
        val progressivePref = pickByResolution(progressives, listOf("720p", "480p", "360p", "240p"))
            ?: error("Nenhum stream de vídeo disponível para $youtubeUrl")

        Log.w(TAG, "  → fallback progressivo ${progressivePref.resolution} mp4 (adaptive falhou)")
        StreamSource(
            videoUrl = progressivePref.content,
            audioUrl = null,
            title = info.name ?: "",
            durationSeconds = info.duration,
            resolution = progressivePref.resolution ?: "?"
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
