package com.oftalmocenter.tv.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Encapsula o TextToSpeech do Android com uma API suspend-friendly.
 *
 * Comportamento:
 *  - Inicialização assíncrona; `speak()` aguarda o init terminar.
 *  - Tenta pt-BR → pt → default; se nenhum suportar, ainda fala (default
 *    do device), mas loga warning. App não quebra por TTS faltando.
 *  - O `setOnUtteranceProgressListener` é único pra evitar race entre
 *    chamadas concorrentes; cada speak gera um utteranceId único e o
 *    callback resolve o pendingCallbacks[id].
 *  - speak() retorna `true` quando o TTS realmente terminou de falar,
 *    `false` em erro/timeout/init-falhou.
 */
class TTSManager(context: Context) {

    companion object {
        private const val TAG = "TTSManager"
    }

    private val initDeferred = CompletableDeferred<Boolean>()
    private val pendingCallbacks = ConcurrentHashMap<String, (Boolean) -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "init falhou (status=$status) — TTS indisponível")
            initDeferred.complete(false)
            return@TextToSpeech
        }
        applyLanguageWithFallback()
        runCatching {
            tts.setSpeechRate(0.95f)
            tts.setPitch(1.0f)
        }
        Log.i(TAG, "TTS inicializado com sucesso")
        initDeferred.complete(true)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "onStart: $utteranceId")
            }
            override fun onDone(utteranceId: String?) {
                Log.i(TAG, "onDone: $utteranceId")
                utteranceId?.let { pendingCallbacks.remove(it)?.invoke(true) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "onError: $utteranceId")
                utteranceId?.let { pendingCallbacks.remove(it)?.invoke(false) }
            }
        })
    }

    private fun applyLanguageWithFallback() {
        val tries = listOf(Locale("pt", "BR"), Locale("pt"), Locale.getDefault())
        for (locale in tries) {
            val res = runCatching { tts.setLanguage(locale) }.getOrNull() ?: continue
            if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.i(TAG, "idioma definido: $locale (res=$res)")
                return
            }
            Log.w(TAG, "idioma indisponível: $locale (res=$res)")
        }
        Log.w(TAG, "nenhum idioma pt disponível; usando default sem garantias")
    }

    /**
     * Fala o texto e aguarda terminar. Volume 0-100. Retorna true se a fala
     * concluiu sem erros, false em erro/timeout/init-falhou.
     */
    suspend fun speak(text: String, volumePercent: Int): Boolean {
        val ready = initDeferred.await()
        if (!ready) return false
        if (text.isBlank()) return false

        return suspendCancellableCoroutine { cont ->
            val utteranceId = "u-${System.nanoTime()}"
            pendingCallbacks[utteranceId] = { success ->
                if (cont.isActive) cont.resume(success)
            }
            cont.invokeOnCancellation { pendingCallbacks.remove(utteranceId) }

            val params = Bundle().apply {
                putFloat(
                    TextToSpeech.Engine.KEY_PARAM_VOLUME,
                    (volumePercent.coerceIn(0, 100) / 100f)
                )
            }
            val rc = runCatching {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            }.getOrElse { TextToSpeech.ERROR }

            if (rc != TextToSpeech.SUCCESS) {
                Log.w(TAG, "tts.speak retornou rc=$rc; finalizando como erro")
                pendingCallbacks.remove(utteranceId)?.invoke(false)
            }
        }
    }

    fun release() {
        runCatching {
            tts.stop()
            tts.shutdown()
        }
        pendingCallbacks.clear()
    }
}
