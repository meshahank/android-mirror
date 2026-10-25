package com.shahan.androidmirror.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import com.shahan.androidmirror.protocol.Control
import com.shahan.androidmirror.protocol.Envelope
import com.shahan.androidmirror.protocol.PairRequest
import com.shahan.androidmirror.protocol.PairResult
import com.shahan.androidmirror.protocol.SessionRequest
import com.shahan.androidmirror.protocol.SessionStatus
import com.shahan.androidmirror.protocol.Signal
import com.shahan.androidmirror.protocol.Wire
import com.shahan.androidmirror.protocol.validEnvelope
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromString
import kotlinx.serialization.json.jsonPrimitive
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

    companion object {
        const val PORT = 38475
        const val SERVICE = "_android-mirror._tcp."
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paired = ConcurrentHashMap<String, String>()
    private val active = AtomicReference<DefaultWebSocketServerSession?>(null)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val server = embeddedServer(CIO, host = "0.0.0.0", port = PORT) {
        install(WebSockets)
        routing { webSocket("/signal") { handle(this) } }
    }
    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
    }

    val shortCode = (100000 + SecureRandom().nextInt(900000)).toString()
    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: Wire.id()

    fun start() {
        context.getSharedPreferences("mirror", Context.MODE_PRIVATE).edit().putString("pair_code", shortCode).apply()
        server.start(wait = false)
        nsd.registerService(NsdServiceInfo().apply {
            serviceName = "AndroidMirror"
            serviceType = SERVICE
            port = PORT
            setAttribute("v", "1")
            setAttribute("id", deviceId.take(12))
        }, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    fun stop() {
        active.getAndSet(null)?.let { socket -> scope.launch { socket.close() } }
        runCatching { nsd.unregisterService(registration) }
        server.stop(500, 1_500)
    }

    fun sendSignal(signal: Signal) {
        val socket = active.get() ?: return
        scope.launch {
            val envelope = Envelope<Signal>(type = "signal", body = signal)
            socket.send(Frame.Text(Wire.json.encodeToString(envelope)))
        }
    }

    private suspend fun handle(ws: DefaultWebSocketServerSession) {
        ws.incoming.consumeEach { frame ->
            if (frame !is Frame.Text) {
                ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "text required"))
                return@consumeEach
            }
            val raw = frame.readText()
            if (!validEnvelope(raw)) {
                ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid message"))
                return@consumeEach
            }
            val json = Wire.json.decodeFromString<JsonObject>(raw)
            val type = json["type"]?.jsonPrimitive?.content ?: return@consumeEach
            val body = json["body"] ?: return@consumeEach
            when (type) {
                "pair-request" -> {
                    val request = Wire.json.decodeFromJsonElement<PairRequest>(body)
                    val allowed = request.shortCode == shortCode && request.clientPublicKey.length in 16..8192 && callbacks.pairRequested(request.computerName.take(60), request.clientPublicKey)
                    if (allowed) paired[request.clientPublicKey] = request.computerName
                    val response = Envelope<PairResult>(type = "pair-result", body = PairResult(allowed, deviceId, android.os.Build.MODEL, if (allowed) null else "Rejected or expired"))
                    ws.send(Frame.Text(Wire.json.encodeToString(response)))
                }
                "session-request" -> {
                    val request = Wire.json.decodeFromJsonElement<SessionRequest>(body)
                    val allowed = paired.containsKey(request.clientId) && callbacks.sessionRequested(request.clientId)
                    if (allowed) active.set(ws)
                    val response = Envelope<SessionStatus>(type = "session-status", body = SessionStatus(if (allowed) "capture-starting" else "rejected"))
                    ws.send(Frame.Text(Wire.json.encodeToString(response)))
                }
                "signal" -> if (active.get() === ws) callbacks.signal(Wire.json.decodeFromJsonElement<Signal>(body))
                "control" -> if (active.get() === ws) callbacks.control(Wire.json.decodeFromJsonElement<Control>(body))
            }
        }
    }
}
