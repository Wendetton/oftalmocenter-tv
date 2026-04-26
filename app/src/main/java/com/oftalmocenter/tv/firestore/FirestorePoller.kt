package com.oftalmocenter.tv.firestore

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente REST do Firestore (sem SDK Firebase). Faz polling do projeto
 * `webtv-ee904` via HTTPS direto.
 *
 * Endpoint:
 *   GET https://firestore.googleapis.com/v1/projects/{projectId}/
 *       databases/(default)/documents/{path}?key={apiKey}
 *
 * O JSON retornado vem com cada campo embrulhado em um discriminador de
 * tipo (`stringValue`, `integerValue`, `doubleValue`, etc.). Este cliente
 * oferece helpers que extraem o valor desembrulhado.
 *
 * Sem autenticação — confirmado por inspeção do app web (utils/firebase.js
 * e pages/_app.js do Wendetton/webtv): as Security Rules do projeto são
 * abertas para os paths consumidos pela TV.
 */
/**
 * Configuração de áudio lida de `config/main`. Espelha os campos que o
 * app web define no admin. Todos com defaults seguros caso o documento
 * esteja faltando algum campo.
 */
data class AudioConfig(
    val duckVolume: Int = 0,
    val restoreVolume: Int = 60,
    val announceVolume: Int = 100,
    val leadMs: Long = 450L,
    val template: String = "Atenção: paciente {{nome}}. Dirija-se à sala {{salaTxt}}."
)

class FirestorePoller(
    private val projectId: String,
    private val apiKey: String,
    private val onVideoIdChanged: (String?) -> Unit,
    private val onVolumeChanged: (Int) -> Unit,
    private val onCallStateChanged: (idle: Boolean, nome: String?, sala: String?) -> Unit,
    private val onAudioConfigChanged: (AudioConfig) -> Unit
) {

    companion object {
        private const val TAG = "FirestorePoller"
        private const val CONFIG_POLL_INTERVAL_MS = 10_000L
        private const val ANNOUNCE_POLL_INTERVAL_MS = 1_000L
        private const val DEFAULT_VOLUME = 50
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var configJob: Job? = null
    private var announceJob: Job? = null

    private var lastVideoId: String? = null
    private var lastVolume: Int? = null
    private var lastAnnounceNonce: String? = null
    private var lastAudioConfig: AudioConfig? = null

    fun start() {
        if (configJob == null || configJob?.isActive == false) {
            Log.i(TAG, "iniciando polling de config (${CONFIG_POLL_INTERVAL_MS}ms)")
            configJob = scope.launch {
                while (isActive) {
                    pollConfigOnce()
                    delay(CONFIG_POLL_INTERVAL_MS)
                }
            }
        }
        if (announceJob == null || announceJob?.isActive == false) {
            Log.i(TAG, "iniciando polling de announce (${ANNOUNCE_POLL_INTERVAL_MS}ms)")
            announceJob = scope.launch {
                while (isActive) {
                    pollAnnounceOnce()
                    delay(ANNOUNCE_POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun stop() {
        Log.i(TAG, "parando polling")
        configJob?.cancel(); configJob = null
        announceJob?.cancel(); announceJob = null
    }

    private suspend fun pollConfigOnce() {
        try {
            // Fonte do vídeo: coleção `ytPlaylist`. Pega o doc de menor `order`
            // com URL/videoId válido. Carrossel entre múltiplos itens é fase
            // futura. Fallback: `config/main.videoId` (esquema antigo).
            val videoId = pickVideoIdFromPlaylist() ?: pickVideoIdFromConfigMain()
            if (videoId != lastVideoId) {
                Log.i(TAG, "videoId: $lastVideoId → $videoId")
                lastVideoId = videoId
                withContext(Dispatchers.Main) { onVideoIdChanged(videoId) }
            }

            // Volume do slider em tempo real (admin pode ajustar dinamicamente).
            fetchDocument("config/control")?.let { doc ->
                val volume = (doc.fieldInt("ytVolume") ?: DEFAULT_VOLUME).coerceIn(0, 100)
                if (volume != lastVolume) {
                    Log.i(TAG, "config/control.ytVolume: $lastVolume → $volume")
                    lastVolume = volume
                    withContext(Dispatchers.Main) { onVolumeChanged(volume) }
                }
            }

            // Config de áudio (duck/restore/announce/leadMs/template).
            fetchDocument("config/main")?.let { doc ->
                val cfg = AudioConfig(
                    duckVolume = (doc.fieldInt("duckVolume") ?: 0).coerceIn(0, 100),
                    restoreVolume = (doc.fieldInt("restoreVolume") ?: 60).coerceIn(0, 100),
                    announceVolume = (doc.fieldInt("announceVolume") ?: 100).coerceIn(0, 100),
                    leadMs = (doc.fieldInt("leadMs") ?: 450).coerceIn(0, 5000).toLong(),
                    template = doc.fieldString("announceTemplate")?.takeIf { it.isNotBlank() }
                        ?: "Atenção: paciente {{nome}}. Dirija-se à sala {{salaTxt}}."
                )
                if (cfg != lastAudioConfig) {
                    Log.i(TAG, "config/main áudio: $cfg")
                    lastAudioConfig = cfg
                    withContext(Dispatchers.Main) { onAudioConfigChanged(cfg) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "erro no poll: ${t.message}")
        }
    }

    /**
     * Pega o estado de chamada do `config/announce`. Dispara o callback
     * sempre que o `nonce` muda (nova chamada) E quando volta a `idle=true`
     * — esse último caso garante que o overlay seja escondido no fim.
     */
    private suspend fun pollAnnounceOnce() {
        try {
            val doc = fetchDocument("config/announce") ?: return
            val nonce = doc.fieldString("nonce") ?: return

            // Disparar quando o nonce muda OU quando o estado idle pode ter
            // mudado mesmo com mesmo nonce (raro, mas defensivo: o admin web
            // pode setar idle=true mantendo o nonce).
            val idle = doc.fieldBoolean("idle") ?: true
            val nome = doc.fieldString("nome")?.takeIf { it.isNotBlank() }
            val sala = doc.fieldString("sala")?.takeIf { it.isNotBlank() }

            val nonceChanged = nonce != lastAnnounceNonce
            if (!nonceChanged && idle) {
                // Estado já está idle desde a última checagem; nada mudou.
                return
            }
            lastAnnounceNonce = nonce
            Log.i(TAG, "announce: nonce=$nonce idle=$idle nome=$nome sala=$sala")
            withContext(Dispatchers.Main) { onCallStateChanged(idle, nome, sala) }
        } catch (t: Throwable) {
            Log.w(TAG, "erro no pollAnnounce: ${t.message}")
        }
    }

    private fun pickVideoIdFromPlaylist(): String? {
        val collection = fetchDocument("ytPlaylist") ?: return null
        val docs = collection.optJSONArray("documents") ?: return null

        // Ordenar por campo `order` (asc); pegar o primeiro com videoId válido.
        return (0 until docs.length())
            .map { docs.getJSONObject(it) }
            .sortedBy { it.fieldInt("order") ?: Int.MAX_VALUE }
            .firstNotNullOfOrNull { doc ->
                doc.fieldString("videoId")?.takeIf { it.isNotBlank() }
                    ?: doc.fieldString("url")?.let { extractYoutubeVideoId(it) }
            }
    }

    private fun pickVideoIdFromConfigMain(): String? {
        return fetchDocument("config/main")
            ?.fieldString("videoId")
            ?.takeIf { it.isNotBlank() }
    }

    private fun fetchDocument(path: String): JSONObject? {
        val url = "https://firestore.googleapis.com/v1/projects/$projectId/" +
            "databases/(default)/documents/$path?key=$apiKey"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "$path → HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            return JSONObject(body)
        }
    }
}

/** Lê `fields.<name>.stringValue` do JSON do Firestore REST. */
private fun JSONObject.fieldString(name: String): String? {
    return optJSONObject("fields")
        ?.optJSONObject(name)
        ?.let { if (it.has("stringValue")) it.optString("stringValue") else null }
}

/** Lê `fields.<name>.integerValue` ou `doubleValue` do JSON do Firestore REST. */
private fun JSONObject.fieldInt(name: String): Int? {
    val field = optJSONObject("fields")?.optJSONObject(name) ?: return null
    if (field.has("integerValue")) return field.optString("integerValue").toIntOrNull()
    if (field.has("doubleValue")) return field.optDouble("doubleValue").toInt()
    return null
}

/** Lê `fields.<name>.booleanValue` do JSON do Firestore REST. */
private fun JSONObject.fieldBoolean(name: String): Boolean? {
    val field = optJSONObject("fields")?.optJSONObject(name) ?: return null
    if (field.has("booleanValue")) return field.optBoolean("booleanValue")
    return null
}

private val YT_ID_REGEX = Regex(
    // Ordem importa: a primeira correspondência válida vence.
    """(?:v=|youtu\.be/|/embed/|/shorts/|/v/)([A-Za-z0-9_-]{6,})"""
)

/**
 * Extrai o videoId de uma URL do YouTube. Suporta os formatos comuns:
 *   https://www.youtube.com/watch?v=ID&t=11s
 *   https://youtu.be/ID
 *   https://www.youtube.com/embed/ID
 *   https://www.youtube.com/shorts/ID
 */
private fun extractYoutubeVideoId(url: String): String? {
    return YT_ID_REGEX.find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
}
