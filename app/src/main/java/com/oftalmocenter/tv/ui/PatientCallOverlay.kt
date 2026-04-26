package com.oftalmocenter.tv.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.oftalmocenter.tv.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Controlador da camada de UI sobre o vídeo. Tem dois estados:
 *
 *  - IDLE: barra discreta no canto inferior direito com relógio + logo.
 *  - CHAMANDO: painel grande no rodapé com nome do paciente e sala.
 *
 * As Views são criadas em XML (activity_main.xml). Esta classe só
 * orquestra estado e animações — fade+slide com ViewPropertyAnimator,
 * sem libs externas.
 *
 * Não faz polling nem TTS; só recebe `showCall(nome, sala)` ou `hideCall()`
 * de quem orquestra. Áudio (TTS, duck/restore) é responsabilidade do
 * AudioOrchestrator na Fase 5.
 */
class PatientCallOverlay(rootView: View) {

    private val callPanel: View = rootView.findViewById(R.id.call_panel)
    private val callName: TextView = rootView.findViewById(R.id.call_name)
    private val callRoom: TextView = rootView.findViewById(R.id.call_room)
    private val idleClock: TextView = rootView.findViewById(R.id.idle_clock)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clockFormatter = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    private val clockTick = object : Runnable {
        override fun run() {
            idleClock.text = clockFormatter.format(Date())
            // Atualizar a cada 30s é suficiente — relógio em HH:mm não precisa de
            // segundos. Reagenda alinhado ao próximo minuto cheio para reduzir
            // drift do relógio.
            val now = System.currentTimeMillis()
            val nextMinute = ((now / 60_000L) + 1) * 60_000L
            mainHandler.postDelayed(this, (nextMinute - now).coerceAtLeast(5_000L))
        }
    }

    private var isShowing = false

    fun start() {
        mainHandler.post(clockTick)
    }

    fun stop() {
        mainHandler.removeCallbacks(clockTick)
        callPanel.animate().cancel()
    }

    /**
     * Exibe o painel de chamada com o nome e a sala. Idempotente — chamadas
     * sucessivas com os mesmos valores não disparam animação. Mudança de
     * valores troca o texto e mantém o painel visível (sem flash).
     */
    fun showCall(nome: String, sala: String) {
        val nomeNormalizado = formatPatientName(nome)
        val salaNormalizada = formatRoom(sala)

        if (isShowing && callName.text == nomeNormalizado && callRoom.text == salaNormalizada) {
            return
        }
        callName.text = nomeNormalizado
        callRoom.text = salaNormalizada

        if (isShowing) return

        isShowing = true
        callPanel.animate().cancel()
        callPanel.visibility = View.VISIBLE
        callPanel.alpha = 0f
        callPanel.translationY = SLIDE_DISTANCE_PX
        callPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(SHOW_DURATION_MS)
            .start()
    }

    /** Esconde o painel de chamada com fade out + slide down. */
    fun hideCall() {
        if (!isShowing) return
        isShowing = false
        callPanel.animate().cancel()
        callPanel.animate()
            .alpha(0f)
            .translationY(SLIDE_DISTANCE_PX)
            .setDuration(HIDE_DURATION_MS)
            .withEndAction { callPanel.visibility = View.GONE }
            .start()
    }

    /**
     * Aplica o estado vindo do Firestore (`config/announce`). Centraliza a
     * decisão de mostrar/esconder em um único ponto, evitando estados
     * inconsistentes na UI.
     */
    fun applyState(idle: Boolean, nome: String?, sala: String?) {
        if (idle || nome.isNullOrBlank() || sala.isNullOrBlank()) {
            hideCall()
        } else {
            showCall(nome, sala)
        }
    }

    /**
     * Converte "JOÃO DA SILVA" → "João da Silva" para evitar que o TTS
     * (Fase 5) leia letra por letra e melhorar a apresentação visual.
     * Tratamento simples: capitaliza a primeira letra de cada palavra
     * com mais de 2 caracteres; preposições curtas ficam minúsculas.
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

    /**
     * Sala chega com formato livre ("Consultório 2", "Sala 3", "Cons. 1"...).
     * Mantém como está, só faz trim e capitaliza a primeira letra.
     */
    private fun formatRoom(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.isEmpty()) "" else
            trimmed.replaceFirstChar { it.titlecase(Locale("pt", "BR")) }
    }

    companion object {
        private const val SHOW_DURATION_MS = 500L
        private const val HIDE_DURATION_MS = 400L
        private const val SLIDE_DISTANCE_PX = 80f
    }
}
