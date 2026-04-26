package com.oftalmocenter.tv.ui

import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.oftalmocenter.tv.R
import com.oftalmocenter.tv.cache.CallHistoryStore
import java.util.Locale

/**
 * Controlador da camada de UI sobre o vídeo. Compõe dois elementos:
 *
 *  - **Faixa inferior (sempre visível):** logo + cabeçalho "ÚLTIMAS CHAMADAS"
 *    + lista das 3 últimas chamadas. Altura ajustada para 12% da tela.
 *  - **Card flutuante (só durante chamada):** painel branco com cantos
 *    arredondados centralizado, exibindo o paciente atual em destaque.
 *
 * Tema claro premium, paleta da marca Oftalmocenter (azul escuro/claro).
 *
 * Histórico persistido em SharedPreferences via [CallHistoryStore],
 * sobrevive a restart e expira após 30min.
 */
class PatientCallOverlay(
    rootView: View,
    private val historyStore: CallHistoryStore,
    displayMetrics: DisplayMetrics
) {

    companion object {
        private const val TAG = "PatientCallOverlay"
        private const val SHOW_DURATION_MS = 600L
        private const val HIDE_DURATION_MS = 400L
        private const val SCALE_FROM = 0.92f
    }

    // Faixa inferior
    private val bottomBar: LinearLayout = rootView.findViewById(R.id.bottom_bar)
    private val historyRow1: LinearLayout = rootView.findViewById(R.id.history_row_1)
    private val historyRow2: LinearLayout = rootView.findViewById(R.id.history_row_2)
    private val historyRow3: LinearLayout = rootView.findViewById(R.id.history_row_3)
    private val historyName1: TextView = rootView.findViewById(R.id.history_name_1)
    private val historyRoom1: TextView = rootView.findViewById(R.id.history_room_1)
    private val historyName2: TextView = rootView.findViewById(R.id.history_name_2)
    private val historyRoom2: TextView = rootView.findViewById(R.id.history_room_2)
    private val historyName3: TextView = rootView.findViewById(R.id.history_name_3)
    private val historyRoom3: TextView = rootView.findViewById(R.id.history_room_3)

    // Card central
    private val callCard: View = rootView.findViewById(R.id.call_card)
    private val callName: TextView = rootView.findViewById(R.id.call_name)
    private val callRoom: TextView = rootView.findViewById(R.id.call_room)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isShowing = false

    /** Reagenda re-render do histórico para limpar entradas expiradas. */
    private val refreshHistoryTick = object : Runnable {
        override fun run() {
            renderHistory()
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    init {
        // Ajusta a altura da faixa inferior para 12% da altura da tela.
        val targetHeightPx = (displayMetrics.heightPixels * 0.12f).toInt()
        bottomBar.layoutParams = bottomBar.layoutParams.apply { height = targetHeightPx }
        Log.i(TAG, "bottom_bar height ajustada para ${targetHeightPx}px (12% de ${displayMetrics.heightPixels}px)")
    }

    fun start() {
        renderHistory()
        mainHandler.postDelayed(refreshHistoryTick, 60_000L)
    }

    fun stop() {
        mainHandler.removeCallbacks(refreshHistoryTick)
        callCard.animate().cancel()
    }

    /**
     * Mostra o card de chamada com o paciente em destaque. Adiciona ao
     * histórico imediatamente — assim, quando o card sumir, o paciente
     * já estará na faixa inferior. Idempotente em conteúdo igual.
     */
    fun showCall(nome: String, sala: String) {
        val displayName = formatPatientName(nome)
        val displayRoom = formatRoomForCard(sala)

        Log.i(TAG, "showCall(in): nome='$nome' sala='$sala' → display='$displayName'/'$displayRoom'")

        historyStore.add(nome, sala)
        renderHistory()

        if (isShowing && callName.text == displayName && callRoom.text == displayRoom) {
            Log.i(TAG, "showCall: idempotente, conteúdo igual ao já exibido")
            return
        }
        callName.text = displayName
        callRoom.text = displayRoom

        // Garantir visibility VISIBLE explicitamente — algum cenário pode
        // ter deixado o filho em estado inconsistente.
        callName.visibility = View.VISIBLE
        callRoom.visibility = View.VISIBLE
        callName.requestLayout()
        callRoom.requestLayout()
        callCard.requestLayout()

        Log.i(TAG, "showCall: callName.text='${callName.text}' visibility=${callName.visibility}")
        Log.i(TAG, "showCall: callRoom.text='${callRoom.text}' visibility=${callRoom.visibility}")

        if (isShowing) {
            Log.i(TAG, "showCall: card já visível, apenas trocou conteúdo")
            return
        }

        isShowing = true
        callCard.animate().cancel()
        callCard.visibility = View.VISIBLE
        callCard.alpha = 0f
        callCard.scaleX = SCALE_FROM
        callCard.scaleY = SCALE_FROM
        callCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SHOW_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun hideCall() {
        if (!isShowing) return
        isShowing = false
        callCard.animate().cancel()
        callCard.animate()
            .alpha(0f)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(HIDE_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                callCard.visibility = View.GONE
                callCard.scaleX = 1f
                callCard.scaleY = 1f
            }
            .start()
    }

    /**
     * Aplica o estado vindo do Firestore. Mantido por compatibilidade
     * — em chamadas reais, MainActivity usa o AudioOrchestrator que
     * chama showCall/hideCall diretamente após o ciclo de áudio.
     */
    fun applyState(idle: Boolean, nome: String?, sala: String?) {
        if (idle || nome.isNullOrBlank() || sala.isNullOrBlank()) {
            hideCall()
        } else {
            showCall(nome, sala)
        }
    }

    private fun renderHistory() {
        val items = historyStore.recent()
        Log.i(TAG, "renderHistory: ${items.size} entries — ${items.joinToString { "${it.nome}/${it.sala}" }}")
        renderRow(historyRow1, historyName1, historyRoom1, items.getOrNull(0))
        renderRow(historyRow2, historyName2, historyRoom2, items.getOrNull(1))
        renderRow(historyRow3, historyName3, historyRoom3, items.getOrNull(2))
    }

    private fun renderRow(
        row: View,
        nameView: TextView,
        roomView: TextView,
        entry: CallHistoryStore.Entry?
    ) {
        if (entry == null) {
            row.visibility = View.INVISIBLE  // mantém espaço, evita "pulos" no layout
            return
        }
        row.visibility = View.VISIBLE
        nameView.visibility = View.VISIBLE
        roomView.visibility = View.VISIBLE
        nameView.text = formatPatientName(entry.nome)
        roomView.text = formatRoomForHistory(entry.sala)
        nameView.requestLayout()
        roomView.requestLayout()
        Log.i(
            TAG,
            "renderRow: name='${nameView.text}' room='${roomView.text}' rowVis=${row.visibility}"
        )
    }

    /**
     * Formata sala para o card central. "2" → "Consultório 2".
     * Se a sala já vier com texto descritivo ("Sala 3", "Cons. 1"), preserva.
     */
    private fun formatRoomForCard(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> ""
            trimmed.toIntOrNull() != null -> "Consultório $trimmed"
            else -> trimmed.replaceFirstChar { it.titlecase(Locale("pt", "BR")) }
        }
    }

    /** No histórico, mesmo formato compacto: "Consultório 2". */
    private fun formatRoomForHistory(raw: String): String = formatRoomForCard(raw)

    /**
     * "JOÃO DA SILVA" → "João da Silva". Capitaliza primeira letra de
     * cada palavra, exceto preposições curtas. Melhora a apresentação
     * visual e a leitura do TTS.
     */
    private fun formatPatientName(raw: String): String {
        val small = setOf("da", "de", "do", "das", "dos", "e")
        return raw.trim().lowercase(Locale("pt", "BR"))
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                if (word.length <= 2 && small.contains(word)) word
                else word.replaceFirstChar { it.titlecase(Locale("pt", "BR")) }
            }
    }
}
