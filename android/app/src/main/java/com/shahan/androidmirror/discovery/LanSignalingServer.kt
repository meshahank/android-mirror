package com.shahan.androidmirror.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import com.shahan.androidmirror.protocol.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Local-only signalling endpoint. Pairing code is shown only on the unlocked phone. */
class LanSignalingServer(private val context: Context, private val callbacks: Callbacks) {
    interface Callbacks { fun pairRequested(name: String, key: String): Boolean; fun sessionRequested(clientId: String): Boolean; fun signal(signal: Signal); fun control(control: Control) }
    companion object { const val PORT = 38475; const val SERVICE = "_android-mirror._tcp." }
    private val paired = ConcurrentHashMap<String, String>()
    private val active = AtomicReference<DefaultWebSocketServerSession?>(null)
    private var server = embeddedServer(CIO, host = "0.0.0.0", port = PORT) { install(WebSockets); routing { webSocket("/signal") { handle(this) } } }
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val registration = object : NsdManager.RegistrationListener { override fun onServiceRegistered(info: NsdServiceInfo) = Unit; override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) = Unit; override fun onServiceUnregistered(i: NsdServiceInfo) = Unit; override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) = Unit }
    val shortCode = (100000 + SecureRandom().nextInt(900000)).toString()
    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: Wire.id()
    fun start() { context.getSharedPreferences("mirror", Context.MODE_PRIVATE).edit().putString("pair_code", shortCode).apply(); server.start(false); nsd.registerService(NsdServiceInfo().apply { serviceName = "AndroidMirror-${android.os.Build.MODEL.take(18)}"; serviceType = SERVICE; port = PORT; setAttribute("v", "1"); setAttribute("id", deviceId.take(12)) }, NsdManager.PROTOCOL_DNS_SD, registration) }
    fun stop() { active.getAndSet(null)?.close(); runCatching { nsd.unregisterService(registration) }; server.stop(500, 1500) }
    fun sendSignal(signal: Signal) { active.get()?.let { ws -> CoroutineScope(Dispatchers.IO).launch { ws.send(Frame.Text(Wire.json.encodeToString(Envelope("signal", body = signal)))) } } }
    private suspend fun handle(ws: DefaultWebSocketServerSession) {
        ws.incoming.consumeEach { frame -> if (frame !is Frame.Text || !validEnvelope(frame.readText())) { ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid")); return@consumeEach }; val obj = Wire.json.parseToJsonElement(frame.readText()).jsonObject; val type = obj["type"]?.jsonPrimitive?.content ?: return@consumeEach; val body = obj["body"] ?: return@consumeEach
            when (type) {
                "pair-request" -> { val p = Wire.json.decodeFromJsonElement<PairRequest>(body); val allowed = p.shortCode == shortCode && p.clientPublicKey.length in 16..8192 && callbacks.pairRequested(p.computerName.take(60), p.clientPublicKey); if (allowed) paired[p.clientPublicKey] = p.computerName; ws.send(Frame.Text(Wire.json.encodeToString(Envelope("pair-result", body = PairResult(allowed, deviceId, android.os.Build.MODEL, if (allowed) null else "Rejected or expired"))))) }
                "session-request" -> { val s = Wire.json.decodeFromJsonElement<SessionRequest>(body); val ok = paired.containsKey(s.clientId) && callbacks.sessionRequested(s.clientId); if (ok) active.set(ws); ws.send(Frame.Text(Wire.json.encodeToString(Envelope("session-status", body = SessionStatus(if (ok) "capture-starting" else "rejected"))))) }
                "signal" -> if (active.get() === ws) callbacks.signal(Wire.json.decodeFromJsonElement(body))
                "control" -> if (active.get() === ws) callbacks.control(Wire.json.decodeFromJsonElement(body))
            }
        }
    }
}
