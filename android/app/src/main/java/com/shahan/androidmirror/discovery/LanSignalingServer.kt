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

    val shortCode = context.getSharedPreferences("mirror", Context.MODE_PRIVATE)
        .getString("pair_code", null) ?: (100000 + SecureRandom().nextInt(900000)).toString()
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
        active.getAndSet(null)?.let { socket ->
            scope.launch {
                try { socket.close() } catch (_: Exception) {}
            }
        }
        runCatching { nsd.unregisterService(registration) }
        server.stop(500, 1500)
    }

    fun sendSignal(signal: Signal) {
        val socket = active.get() ?: return
        scope.launch {
            try {
                socket.send(Frame.Text(Wire.json.encodeToString(Envelope(type = "signal", body = signal))))
            } catch (_: Exception) {}
        }
    }

    private suspend fun status(ws: DefaultWebSocketServerSession, state: String, message: String? = null) {
        ws.send(Frame.Text(Wire.json.encodeToString(Envelope(type = "session-status", body = SessionStatus(state, message)))))
    }

    private suspend fun handle(ws: DefaultWebSocketServerSession) {
        try {
            ws.incoming.consumeEach { frame ->
                if (frame !is Frame.Text) return@consumeEach
                try {
                    val root = Wire.json.decodeFromString<JsonObject>(frame.readText())
                    val type = root["type"]?.jsonPrimitive?.content ?: return@consumeEach
                    val body = root["body"] ?: return@consumeEach
                    when (type) {
                        "pair-request" -> {
                            val request = Wire.json.decodeFromJsonElement<PairRequest>(body)
                            val accepted = request.shortCode == shortCode && callbacks.pairRequested(request.computerName.take(60), request.clientPublicKey)
                            if (accepted) paired[request.clientPublicKey] = request.computerName
                            val result = Envelope(type = "pair-result", body = PairResult(accepted, deviceId, android.os.Build.MODEL, if (accepted) null else "The code was rejected"))
                            ws.send(Frame.Text(Wire.json.encodeToString(result)))
                        }
                        "session-request" -> {
                            val request = Wire.json.decodeFromJsonElement<SessionRequest>(body)
                            val accepted = paired.containsKey(request.clientId) && callbacks.sessionRequested(request.clientId)
                            if (!accepted) { status(ws, "rejected", "This computer is not paired"); return@consumeEach }
                            active.set(ws)
                            status(ws, "capture-starting", "Pairing approved. Starting screen capture.")
                        }
                        "signal" -> if (active.get() === ws) callbacks.signal(Wire.json.decodeFromJsonElement<Signal>(body))
                        "control" -> if (active.get() === ws) callbacks.control(Wire.json.decodeFromJsonElement<Control>(body))
                    }
                } catch (error: Exception) {
                    status(ws, "error", error.message ?: "Phone could not process the connection message")
                }
            }
        } finally {
            active.compareAndSet(ws, null)
        }
    }
}
