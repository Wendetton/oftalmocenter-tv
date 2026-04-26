package com.oftalmocenter.tv.audio

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.oftalmocenter.tv.player.VideoPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orquestra o fluxo completo de uma chamada de paciente:
 *
 *   1. Mostra o overlay (callback `onCallStarted`)
 *   2. Espera `leadMs` (delay para o duck completar antes da fala)
 *   3. Faz duck do vídeo (fade do volume base → duckVolume)
 *   4. Toca TTS, aguardando concluir (com timeout de 15s)
 *   5. Repete (se repeatCount > 1) com `intervalMs` entre repetições
 *   6. Restaura o vídeo (fade duckVolume → volume base)
 *   7. Mantém o overlay por mais 10s e esconde (callback `onCallEnded`)
 *
 * Múltiplas chamadas viram fila FIFO — uma por vez, sem sobreposição.
 *
 * O "volume base" é mantido aqui como `videoBaseVolume`. Quando o admin
 * mexer no slider de volume durante uma chamada em andamento, o novo
 * valor é guardado e aplicado na hora do restore (passo 6) — evita
 * "salto" de volume durante a fala.
 */
@UnstableApi
class AudioOrchestrator(
    private val player: VideoPlayerManager,
    private val tts: TTSManager,
    private val onCallStarted: (nome: String, sala: String) -> Unit,
    private val onCallEnded: () -> Unit
) {

    companion object {
        private const val TAG = "AudioOrchestrator"
        private const val POST_CALL_HOLD_MS = 10_000L
        private const val DUCK_FADE_MS = 500L
        private const val RESTORE_FADE_MS = 1000L
        private const val TTS_TIMEOUT_MS = 15_000L
        private const val DEFAULT_TEMPLATE =
            "Atenção: paciente {{nome}}. Dirija-se à sala {{salaTxt}}."
    }

    // Configuração ajustável em tempo real via setters thread-safe.
    @Volatile var videoBaseVolume: Int = 50
    @Volatile var duckVolume: Int = 0
    @Volatile var ttsVolume: Int = 100
    @Volatile var leadMs: Long = 450L
    @Volatile var repeatCount: Int = 1
    @Volatile var intervalMs: Long = 3000L
    @Volatile var template: String = DEFAULT_TEMPLATE

    private val callQueue = Channel<CallRequest>(capacity = Channel.UNLIMITED)
    private var consumerJob: Job? = null

    @Volatile private var isProcessingCall = false

    fun start(scope: CoroutineScope) {
        if (consumerJob?.isActive == true) return
        Log.i(TAG, "iniciando consumer da fila de chamadas")
        consumerJob = scope.launch {
            for (req in callQueue) {
                runCatching { executeCall(req) }
                    .onFailure { Log.e(TAG, "erro ao processar chamada: ${it.message}", it) }
            }
        }
    }

    fun stop() {
        consumerJob?.cancel()
        consumerJob = null
    }

    /**
     * Aplica o volume base imediatamente quando não há chamada em andamento.
     * Se houver, o valor é guardado e aplicado no fade de restore — evita
     * "salto" durante a fala.
     */
    fun setVideoBaseVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        videoBaseVolume = clamped
        if (!isProcessingCall) {
            player.setVolume(clamped)
        }
    }

    fun enqueueCall(nome: String, sala: String) {
        Log.i(TAG, "enqueue: nome='$nome' sala='$sala'")
        callQueue.trySend(CallRequest(nome, sala))
    }

    private suspend fun executeCall(req: CallRequest) {
        Log.i(TAG, "→ chamada: '${req.nome}' / sala '${req.sala}'")
        isProcessingCall = true
        try {
            onCallStarted(req.nome, req.sala)

            // 1) Lead-in: dá tempo do paciente "olhar para a tela" + duck completar.
            delay(leadMs.coerceAtLeast(0L))

            // 2) Duck.
            fadeVolume(from = videoBaseVolume, to = duckVolume, durationMs = DUCK_FADE_MS)

            // 3) TTS (com repetições).
            val text = formatText(req.nome, req.sala)
            val n = repeatCount.coerceAtLeast(1)
            for (i in 1..n) {
                if (i > 1) delay(intervalMs.coerceAtLeast(0L))
                val ok = withTimeoutOrNull(TTS_TIMEOUT_MS) { tts.speak(text, ttsVolume) } ?: false
                Log.i(TAG, "fala $i/$n — sucesso=$ok")
                if (!ok && i == 1) {
                    Log.w(TAG, "TTS falhou na primeira tentativa; pulando repetições")
                    break
                }
            }

            // 4) Restore — usa o videoBaseVolume *atual* (admin pode ter mexido).
            fadeVolume(from = duckVolume, to = videoBaseVolume, durationMs = RESTORE_FADE_MS)

            // 5) Pós-chamada: mantém overlay visível por 10s (paciente lê com calma).
            delay(POST_CALL_HOLD_MS)
            Log.i(TAG, "← fim da chamada de '${req.nome}'")
            onCallEnded()
        } finally {
            // Garante que volume base seja restaurado e flag liberada mesmo
            // se algo lançar exceção no meio do fluxo.
            isProcessingCall = false
            player.setVolume(videoBaseVolume)
        }
    }

    private suspend fun fadeVolume(from: Int, to: Int, durationMs: Long) {
        if (from == to || durationMs <= 0L) {
            player.setVolume(to)
            return
        }
        val steps = 20
        val stepMs = (durationMs / steps).coerceAtLeast(10L)
        for (i in 1..steps) {
            val v = from + ((to - from) * i / steps)
            player.setVolume(v)
            delay(stepMs)
        }
        player.setVolume(to)
    }

    /**
     * Aplica o template do `config/main`. Espelha exatamente o que o app web
     * faz em /tv-ducking.js:
     *
     *   {{nome}}    → nome (raw)
     *   {{sala}}    → sala (raw)
     *   {{salaTxt}} → "número X" se sala for não-vazia, ou "" caso contrário
     */
    private fun formatText(nome: String, sala: String): String {
        val salaTxt = if (sala.isBlank()) "" else "número $sala"
        return template
            .replace("{{nome}}", nome)
            .replace("{{sala}}", sala)
            .replace("{{salaTxt}}", salaTxt)
    }

    private data class CallRequest(val nome: String, val sala: String)
}
