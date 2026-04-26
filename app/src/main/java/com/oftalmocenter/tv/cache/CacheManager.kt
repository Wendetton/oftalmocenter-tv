package com.oftalmocenter.tv.cache

import android.content.Context
import android.content.SharedPreferences

/**
 * Cache local com SharedPreferences. Permite que o app inicie offline com
 * o último vídeo conhecido enquanto o Firestore ainda não respondeu, e
 * tolere quedas momentâneas de internet sem perder estado.
 */
class CacheManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveVideoId(videoId: String?) {
        prefs.edit().putString(KEY_VIDEO_ID, videoId).apply()
    }

    fun loadVideoId(): String? = prefs.getString(KEY_VIDEO_ID, null)

    fun saveVolume(percent: Int) {
        prefs.edit().putInt(KEY_VOLUME, percent.coerceIn(0, 100)).apply()
    }

    fun loadVolume(): Int = prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME)

    companion object {
        private const val PREFS_NAME = "webtv_cache_v1"
        private const val KEY_VIDEO_ID = "video_id"
        private const val KEY_VOLUME = "yt_volume"
        private const val DEFAULT_VOLUME = 50
    }
}
