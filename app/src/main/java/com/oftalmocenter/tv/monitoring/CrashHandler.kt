package com.oftalmocenter.tv.monitoring

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * Captura exceções não tratadas e agenda o reinício automático da Activity
 * em ~2 segundos via AlarmManager. Sem isso, qualquer crash pararia a TV
 * até alguém fisicamente reiniciar o Fire TV.
 *
 * Encadeia o handler default depois de agendar o restart, para preservar
 * o comportamento esperado de logging do sistema. Em seguida força o
 * encerramento do processo — sem isso o app pode ficar em estado zumbi.
 */
class CrashHandler(
    private val applicationContext: Context
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private const val RESTART_DELAY_MS = 2_000L
    }

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // O log no Android crash buffer permite diagnóstico via
        // `adb logcat -b crash`.
        Log.e(TAG, "UNCAUGHT em '${thread.name}': ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
        runCatching { scheduleRestart() }
            .onFailure { Log.e(TAG, "falha ao agendar restart: ${it.message}", it) }

        // Encadeia handler default (faz o log padrão do Android).
        runCatching { defaultHandler?.uncaughtException(thread, throwable) }

        // Encerra o processo. Necessário porque algumas exceções deixam
        // o processo em estado inconsistente (SQL state, threads bloqueadas).
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    private fun scheduleRestart() {
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?: return
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        val pi = PendingIntent.getActivity(
            applicationContext,
            /* requestCode = */ 0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarm = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(
            AlarmManager.RTC,
            System.currentTimeMillis() + RESTART_DELAY_MS,
            pi
        )
        Log.i(TAG, "restart agendado em ${RESTART_DELAY_MS}ms")
    }
}
