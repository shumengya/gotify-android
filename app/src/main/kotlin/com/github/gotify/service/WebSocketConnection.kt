package com.github.gotify.service

import android.content.Context
import com.github.gotify.SSLSettings
import com.github.gotify.Utils
import com.github.gotify.api.CertUtils
import com.github.gotify.client.model.Message
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.tinylog.kotlin.Logger

internal class WebSocketConnection(
    context: Context,
    private val baseUrl: String,
    settings: SSLSettings,
    private val token: String?,
    private val reconnectDelay: Duration,
    private val exponentialBackoff: Boolean
) {
    companion object {
        private val ID = AtomicLong(0)
    }

    private val context = context.applicationContext
    private val client: OkHttpClient
    private var errorCount = 0

    private var webSocket: WebSocket? = null
    private lateinit var onMessageCallback: (Message) -> Unit
    private lateinit var onClose: Runnable
    private lateinit var onOpen: Runnable
    private lateinit var onFailure: OnNetworkFailureRunnable
    private lateinit var onReconnected: Runnable
    private var state: State? = null

    init {
        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(1, TimeUnit.MINUTES)
            .connectTimeout(10, TimeUnit.SECONDS)
        CertUtils.applySslSettings(builder, settings)
        client = builder.build()
    }

    @Synchronized
    fun onMessage(onMessage: (Message) -> Unit): WebSocketConnection {
        this.onMessageCallback = onMessage
        return this
    }

    @Synchronized
    fun onClose(onClose: Runnable): WebSocketConnection {
        this.onClose = onClose
        return this
    }

    @Synchronized
    fun onOpen(onOpen: Runnable): WebSocketConnection {
        this.onOpen = onOpen
        return this
    }

    @Synchronized
    fun onFailure(onFailure: OnNetworkFailureRunnable): WebSocketConnection {
        this.onFailure = onFailure
        return this
    }

    @Synchronized
    fun onReconnected(onReconnected: Runnable): WebSocketConnection {
        this.onReconnected = onReconnected
        return this
    }

    private fun request(): Request {
        val url = baseUrl.toHttpUrlOrNull()!!
            .newBuilder()
            .addPathSegment("stream")
            .addQueryParameter("token", token)
            .build()
        return Request.Builder().url(url).get().build()
    }

    @Synchronized
    fun start(): WebSocketConnection {
        if (state == State.Connecting || state == State.Connected) {
            return this
        }
        close()
        state = State.Connecting
        val nextId = ID.incrementAndGet()
        Logger.info("WebSocket($nextId): starting...")

        webSocket = client.newWebSocket(request(), Listener(nextId))
        return this
    }

    @Synchronized
    fun close() {
        cancelReconnect()
        if (webSocket != null) {
            webSocket?.close(1000, "")
            closed()
            Logger.info("WebSocket(${ID.get()}): closing existing connection.")
        }
    }

    @Synchronized
    private fun closed() {
        webSocket = null
        state = State.Disconnected
    }

    fun scheduleReconnectNow(scheduleIn: Duration) = scheduleReconnect(scheduleIn)

    @Synchronized
    fun scheduleReconnect(scheduleIn: Duration) {
        if (state == State.Connecting || state == State.Connected) {
            return
        }
        state = State.Scheduled

        Logger.info("WebSocket: scheduling a service restart in $scheduleIn")
        WebSocketService.scheduleRestart(context, scheduleIn)
    }

    @Synchronized
    private fun cancelReconnect() {
        WebSocketService.cancelScheduledRestart(context)
    }

    private inner class Listener(private val id: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            syncExec(id) {
                state = State.Connected
                Logger.info("WebSocket($id): opened")
                onOpen.run()

                if (errorCount > 0) {
                    onReconnected.run()
                    errorCount = 0
                }
            }
            super.onOpen(webSocket, response)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            syncExec(id) {
                Logger.info("WebSocket($id): received message $text")
                val message = Utils.JSON.fromJson(text, Message::class.java)
                onMessageCallback(message)
            }
            super.onMessage(webSocket, text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            syncExec(id) {
                if (state == State.Connected) {
                    Logger.warn("WebSocket($id): closed")
                    onClose.run()
                }
                closed()
            }
            super.onClosed(webSocket, code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = if (response != null) "StatusCode: ${response.code}" else ""
            val message = response?.message ?: ""
            Logger.error(t) { "WebSocket($id): failure $code Message: $message" }
            syncExec(id) {
                closed()

                errorCount++

                var scheduleIn = reconnectDelay
                if (exponentialBackoff) {
                    scheduleIn *= 2.0.pow(errorCount - 1)
                }
                scheduleIn = scheduleIn.coerceIn(5.seconds..20.minutes)

                onFailure.execute(response?.message ?: "unreachable", scheduleIn)
                scheduleReconnect(scheduleIn)
            }
            super.onFailure(webSocket, t, response)
        }
    }

    @Synchronized
    private fun syncExec(id: Long, runnable: () -> Unit) {
        if (ID.get() == id) {
            runnable()
        }
    }

    internal fun interface OnNetworkFailureRunnable {
        fun execute(status: String, reconnectIn: Duration)
    }

    internal enum class State {
        Scheduled,
        Connecting,
        Connected,
        Disconnected
    }
}
