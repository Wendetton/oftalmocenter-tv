package com.oftalmocenter.tv.player

/**
 * Resultado de uma extração do YouTube. Pode representar dois formatos:
 *
 *  - **Progressivo**: vídeo + áudio em uma única URL mp4. `audioUrl` é null.
 *  - **Adaptive**: vídeo e áudio em URLs separadas (típico de qualidades
 *    altas do YouTube). O ExoPlayer combina via MergingMediaSource.
 *
 * `extractedAtMs` permite calcular o tempo até a re-extração preventiva
 * (URLs do YouTube expiram em algumas horas).
 */
data class StreamSource(
    val videoUrl: String,
    val audioUrl: String?,
    val title: String,
    val durationSeconds: Long,
    val resolution: String,
    val extractedAtMs: Long = System.currentTimeMillis()
) {
    val isAdaptive: Boolean get() = audioUrl != null
}
