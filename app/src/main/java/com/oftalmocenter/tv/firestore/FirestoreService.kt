package com.oftalmocenter.tv.firestore

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.PersistentCacheSettings

/**
 * Listeners do Firestore do projeto `webtv-ee904`. A estrutura espelha a
 * usada pelo app web (Wendetton/webtv), para que o painel admin atual
 * continue controlando a TV sem alterações no momento da migração:
 *
 *   config/main      → campo `videoId` (string, ID do vídeo do YouTube)
 *   config/control   → campo `ytVolume` (number, 0-100)
 *
 * Outras coleções (`calls`, `config/announce`, `ytPlaylist`) são da Fase 5.
 *
 * Persistência offline está habilitada — o Firestore SDK mantém um cache
 * local automaticamente, então o app continua respondendo brevemente sem
 * internet.
 */
class FirestoreService(
    private val onVideoIdChanged: (videoId: String?) -> Unit,
    private val onVolumeChanged: (percent: Int) -> Unit
) {

    companion object {
        private const val TAG = "FirestoreService"
        private const val DEFAULT_VOLUME = 50
    }

    private val db: FirebaseFirestore = FirebaseFirestore.getInstance().apply {
        firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
            .build()
    }

    private var mainListener: ListenerRegistration? = null
    private var controlListener: ListenerRegistration? = null

    fun start() {
        Log.i(TAG, "registrando listeners")

        mainListener = db.collection("config").document("main")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "config/main error: ${err.message}", err)
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) {
                    Log.w(TAG, "config/main não existe")
                    return@addSnapshotListener
                }
                val videoId = snap.getString("videoId")?.trim().takeIf { !it.isNullOrBlank() }
                Log.i(TAG, "config/main.videoId = $videoId")
                onVideoIdChanged(videoId)
            }

        controlListener = db.collection("config").document("control")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "config/control error: ${err.message}", err)
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) {
                    Log.w(TAG, "config/control não existe")
                    return@addSnapshotListener
                }
                val raw = snap.getLong("ytVolume")?.toInt()
                    ?: snap.getDouble("ytVolume")?.toInt()
                    ?: DEFAULT_VOLUME
                val clamped = raw.coerceIn(0, 100)
                Log.i(TAG, "config/control.ytVolume = $clamped")
                onVolumeChanged(clamped)
            }
    }

    fun stop() {
        Log.i(TAG, "removendo listeners")
        mainListener?.remove()
        controlListener?.remove()
        mainListener = null
        controlListener = null
    }
}
