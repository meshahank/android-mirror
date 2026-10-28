package com.shahan.androidmirror.discovery

import android.content.Context
import android.net.nsd.*
import android.provider.Settings
import com.shahan.androidmirror.protocol.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class LanSignalingServer(private val context: Context, private val callbacks: Callbacks) {
    interface Callbacks {
        fun pairRequested(name: String, key: String): Boolean
        fun sessionRequested(clientId: String): Boolean
        fun signal(signal: Signal)
        fun control(control: Control)
    }

    companion object { const val PORT = 38475; const val SERVICE = "_android-mirror._tcp." }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paired = ConcurrentHashMap<String, String>()
    private val active = AtomicReference<DefaultWebSocketServerSession?>(null)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val server = embeddedServer(CIO, host = "0.0.0.0", port = PORT) {
        install(WebSockets)
        routing { webSocket("/signal") { handle(this) } }
    }
    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = Unit
        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
    }

    val shortCode = context.getSharedPreferences("mirror", Context.MODE_PRIVATE).getString("pair_code", null)
        ?: (100000 + SecureRandom().nextInt(900000)).toString()
    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: Wire.id()

    fun start() {
        server.start(false)
        nsd.registerService(NsdServiceInfo().apply {
            serviceName = "AndroidMirror"
            serviceType = SERVICE
            port = PORT
            setAttribute("id", deviceId.take(12))
        }, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    fun stop() {
        active.getAndSet(null)?.let { socket -> scope.launch { try { socket.close() } catch (_: Throwable) {} } }
        runCatching { nsd.unregisterService(registration) }
        server.stop(500, 1500)
    }

    fun sendSignal(signal: Signal) {
        active.get()?.let { socket -> scope.launch { runCatching { socket.send(Frame.Text(Wire.json.encodeToString(Envelope(type = "signal", body = signal))) } } }
    }

    fun sendStatus(state: String, message: String) {
        active.get()?.let { socket -> scope.launch { runCatching { sendStatus(socket, state, message) } } }
    }

    private fun setStage(stage: String) {
        context.getSharedPreferences("mirror", Context.MODE_PRIVATE).edit().putString("session_stage", stage).apply()
    }

    private suspend fun sendStatus(ws: DefaultWebSocketServerSession, state: String, message: String) {
        ws.send(Frame.Text(Wire.json.encodeToString(Envelope(type = "session-status", body = SessionStatus(state, message)))))
    }

    private suspend fun handle(ws: DefaultWebSocketServerSession) {
        setStage("Desktop connected: waiting for pairing request")
        try {
            ws.incoming.consumeEach { frame ->
                if (frame !is Frame.Text) return@consumeEach
                try {
                    val root = Wire.json.decodeFromString<JsonObject>(frame.readText())
                    val type = root["type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Message had no type")
                    val body = root["body"]?.jsonObject ?: throw IllegalArgumentException("Message had no body")
                    when (type) {
                        "pair-request" -> {
                            val request = Wire.json.decodeFromJsonElement<PairRequest>(body)
                            setStage("Pair code received: checking it")
                            val accepted = request.shortCode == shortCode && callbacks.pairRequested(request.computerName.take(60), request.clientPublicKey)
                            if (accepted) paired[request.clientPublicKey] = request.computerName
                            ws.send(Frame.Text(Wire.json.encodeToString(Envelope(type = "pair-result", body = PairResult(accepted, deviceId, android.os.Build.MODEL, if (accepted) null else "The code was rejected")))))
                            setStage(if (accepted) "Pairing approved: waiting for desktop session" else "Pairing rejected: check the 6-digit code")
                        }
                        "session-request" -> {
                            val request = Wire.json.decodeFromJsonElement<SessionRequest>(body)
                            val accepted = paired.containsKey(request.clientId) && callbacks.sessionRequested(request.clientId)
                            if (!accepted) {
                                setStage("Session rejected: this desktop is not paired")
                                sendStatus(ws, "rejected", "This computer is not paired")
                                return@consumeEach
                            }
                            active.set(ws)
                            setStage("Desktop session approved: starting screen capture")
                            sendStatus(ws, "capture-starting", "Pairing approved. Starting screen capture.")
                        }
                        "signal" -> if (active.get() === ws) callbacks.signal(Wire.json.decodeFromJsonElement<Signal>(body))
                        "control" -> if (active.get() === ws) callbacks.control(Wire.json.decodeFromJsonElement<Control>(body))
                        else -> throw IllegalArgumentException("Unknown message type: $type")
                    }
                } catch (error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    setStage("ERROR receiving desktop request: $message")
                    runCatching { sendStatus(ws, "error", message) }
                }
            }
        } catch (error: Throwable) {
            setStage("Desktop connection closed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            active.compareAndSet(ws, null)
        }
    }
}
