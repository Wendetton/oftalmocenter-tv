package com.oftalmocenter.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Inicia o app automaticamente quando o Fire TV liga ou quando o app é atualizado.
 *
 * Intents suportados:
 * - BOOT_COMPLETED: quando o dispositivo termina o boot normal
 * - MY_PACKAGE_REPLACED: quando o APK é atualizado (sideload ou update)
 * - QUICKBOOT_POWERON: reinício rápido em alguns dispositivos Amazon
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val validActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (intent.action in validActions) {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            // Delay de 8 segundos para dar tempo ao Fire TV terminar de inicializar
            // serviços essenciais (WiFi, DNS, etc.) antes de abrir o app
            Handler(Looper.getMainLooper()).postDelayed({
                context.startActivity(launch)
            }, 8000)
        }
    }
}
