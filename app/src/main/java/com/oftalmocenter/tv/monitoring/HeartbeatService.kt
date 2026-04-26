package com.oftalmocenter.tv.monitoring

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Envia status periódico para o Firestore em `tvStatus/heartbeat` a cada
 * 5 minutos. Permite que o admin saiba se a TV está online de longe.
 *
 * Sem autenticação — depende das Security Rules permitirem PATCH em
 * `tvStatus/{*}`. Se o servidor responder 403, o serviço continua
 * tentando (o admin pode ajustar as rules e os heartbeats começam a
 * passar sem rebuild do APK).
 *
 * Não derruba o app em caso de erro: heartbeat é monitoramento
 * opcional, falha local fica em log.
 */
class HeartbeatService(
    private val context: Context,
    private val projectId: String,
    private val apiKey: String,
    private val getCurrentVideoId: () -> String?,
    private val getIsPlaying: () -> Boolean
) {

    companion object {
        private const val TAG = "HeartbeatService"
        private const val INTERVAL_MS = 5L * 60L * 1000L
        private const val DOC_PATH = "tvStatus/heartbeat"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val processStartUptimeMs = SystemClock.elapsedRealtime()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        Log.i(TAG, "iniciando heartbeat (interval=${INTERVAL_MS}ms)")
        job = scope.launch {
            // Primeiro batimento imediato; permite ver no Firestore que o
            // app subiu.
            sendOnce()
            while (isActive) {
                delay(INTERVAL_MS)
                sendOnce()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun sendOnce() {
        try {
            val body = buildPayload()
            val url = "https://firestore.googleapis.com/v1/projects/$projectId/" +
                "databases/(default)/documents/$DOC_PATH?key=$apiKey" +
                "&updateMask.fieldPaths=lastPing" +
                "&updateMask.fieldPaths=appVersion" +
                "&updateMask.fieldPaths=device" +
                "&updateMask.fieldPaths=osVersion" +
                "&updateMask.fieldPaths=freeMemoryMb" +
                "&updateMask.fieldPaths=uptimeMinutes" +
                "&updateMask.fieldPaths=currentVideoId" +
                "&updateMask.fieldPaths=isPlaying"
            val req = Request.Builder()
                .url(url)
                .patch(body.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "heartbeat ok (HTTP ${resp.code})")
                } else {
                    val msg = resp.body?.string()?.take(200)
                    Log.w(TAG, "heartbeat HTTP ${resp.code}: $msg")
                    if (resp.code == 403) {
                        Log.w(TAG, "→ Security Rules do Firestore não permitem PATCH em $DOC_PATH; ajuste as regras se quiser monitor remoto.")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "heartbeat erro: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun buildPayload(): String {
        val nowIso = isoFormatter.format(Date())
        val freeMb = freeMemoryMb()
        val uptimeMin = ((SystemClock.elapsedRealtime() - processStartUptimeMs) / 60_000L)
        val device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        val osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val videoId = getCurrentVideoId()
        val isPlaying = getIsPlaying()
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

        // Construção manual do JSON (sem Gson) — string simples e estável,
        // só strings/integers/timestamps/booleans, sem chars especiais.
        return """{
            "fields": {
                "lastPing":       {"timestampValue": "$nowIso"},
                "appVersion":     {"stringValue": ${jsonString(appVersion)}},
                "device":         {"stringValue": ${jsonString(device)}},
                "osVersion":      {"stringValue": ${jsonString(osVersion)}},
                "freeMemoryMb":   {"integerValue": "$freeMb"},
                "uptimeMinutes":  {"integerValue": "$uptimeMin"},
                "currentVideoId": {"stringValue": ${jsonString(videoId ?: "")}},
                "isPlaying":      {"booleanValue": $isPlaying}
            }
        }"""
    }

    private fun freeMemoryMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    /** Escapa string para JSON sem libs externas. */
    private fun jsonString(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
