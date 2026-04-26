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
class FirestorePoller(
    private val projectId: String,
    private val apiKey: String,
    private val onVideoIdChanged: (String?) -> Unit,
    private val onVolumeChanged: (Int) -> Unit
) {

    companion object {
        private const val TAG = "FirestorePoller"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val DEFAULT_VOLUME = 50
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    private var lastVideoId: String? = null
    private var lastVolume: Int? = null

    fun start() {
        if (pollJob?.isActive == true) return
        Log.i(TAG, "iniciando polling a cada ${POLL_INTERVAL_MS}ms")
        pollJob = scope.launch {
            while (isActive) {
                pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        Log.i(TAG, "parando polling")
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        try {
            fetchDocument("config/main")?.let { doc ->
                val videoId = doc.fieldString("videoId")?.takeIf { it.isNotBlank() }
                if (videoId != lastVideoId) {
                    Log.i(TAG, "config/main.videoId: $lastVideoId → $videoId")
                    lastVideoId = videoId
                    withContext(Dispatchers.Main) { onVideoIdChanged(videoId) }
                }
            }
            fetchDocument("config/control")?.let { doc ->
                val volume = (doc.fieldInt("ytVolume") ?: DEFAULT_VOLUME).coerceIn(0, 100)
                if (volume != lastVolume) {
                    Log.i(TAG, "config/control.ytVolume: $lastVolume → $volume")
                    lastVolume = volume
                    withContext(Dispatchers.Main) { onVolumeChanged(volume) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "erro no poll: ${t.message}")
        }
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
