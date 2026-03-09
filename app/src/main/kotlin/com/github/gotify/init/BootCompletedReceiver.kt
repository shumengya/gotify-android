package com.github.gotify.init

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.gotify.Settings
import com.github.gotify.service.WebSocketService

internal class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = Settings(context)

        if (!settings.tokenExists()) {
            return
        }

        WebSocketService.start(context)
    }
}
