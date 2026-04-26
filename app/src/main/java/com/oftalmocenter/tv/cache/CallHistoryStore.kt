package com.oftalmocenter.tv.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Histórico das últimas N chamadas de paciente, persistido em
 * SharedPreferences. Sobrevive a restart do app (queda de luz, update,
 * crash + auto-restart).
 *
 * Cada entrada expira após 30 minutos — pacientes do dia anterior não
 * voltam a aparecer quando a TV é ligada de manhã.
 *
 * Persistência via JSONArray (built-in do Android, sem deps externas)
 * para evitar problemas com pipes ou outros separadores em nomes
 * de pacientes.
 */
class CallHistoryStore(context: Context) {

    companion object {
        private const val TAG = "CallHistoryStore"
        private const val PREFS_NAME = "call_history_v1"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_SIZE = 3
        private const val MAX_AGE_MS = 30L * 60L * 1000L
    }

    data class Entry(val nome: String, val sala: String, val timestampMs: Long)

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Adiciona uma chamada ao topo do histórico. Evita duplicação
     * imediata: se a chamada mais recente é igual à nova (mesmo nome
     * E sala), não adiciona — apenas atualiza o timestamp.
     */
    fun add(nome: String, sala: String) {
        if (nome.isBlank() || sala.isBlank()) return
        val now = System.currentTimeMillis()
        val current = recent().toMutableList()

        val isDuplicate = current.firstOrNull()?.let {
            it.nome.equals(nome, ignoreCase = true) && it.sala.equals(sala, ignoreCase = true)
        } ?: false

        if (isDuplicate) {
            current[0] = current[0].copy(timestampMs = now)
        } else {
            current.add(0, Entry(nome.trim(), sala.trim(), now))
        }

        save(current.take(MAX_SIZE))
    }

    /**
     * Retorna as entradas mais recentes (até MAX_SIZE), filtrando as
     * que estão fora da janela de validade.
     */
    fun recent(): List<Entry> {
        val now = System.currentTimeMillis()
        return load().filter { now - it.timestampMs <= MAX_AGE_MS }.take(MAX_SIZE)
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun save(items: List<Entry>) {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(JSONObject().apply {
                put("n", e.nome)
                put("s", e.sala)
                put("t", e.timestampMs)
            })
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    private fun load(): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Entry(
                    nome = obj.optString("n", ""),
                    sala = obj.optString("s", ""),
                    timestampMs = obj.optLong("t", 0L)
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "JSON inválido no cache, descartando: ${t.message}")
            emptyList()
        }
    }
}
