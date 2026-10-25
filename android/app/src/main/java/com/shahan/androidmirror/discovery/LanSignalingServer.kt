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
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class LanSignalingServer(private val context: Context, private val callbacks: Callbacks) {
  interface Callbacks { fun pairRequested(name:String,key:String):Boolean; fun sessionRequested(clientId:String):Boolean; fun signal(signal:Signal); fun control(control:Control) }
  companion object { const val PORT=38475; const val SERVICE="_android-mirror._tcp." }
  private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  private val paired=ConcurrentHashMap<String,String>()
  private val active=AtomicReference<DefaultWebSocketServerSession?>(null)
  private val nsd=context.getSystemService(NsdManager::class.java)
  private val server=embeddedServer(CIO,host="0.0.0.0",port=PORT) { install(WebSockets); routing { webSocket("/signal") { handle(this) } } }
  private val registration=object:NsdManager.RegistrationListener {
    override fun onServiceRegistered(info:NsdServiceInfo)=Unit
    override fun onRegistrationFailed(info:NsdServiceInfo,error:Int)=Unit
    override fun onServiceUnregistered(info:NsdServiceInfo)=Unit
    override fun onUnregistrationFailed(info:NsdServiceInfo,error:Int)=Unit
  }
  val shortCode=(100000+SecureRandom().nextInt(900000)).toString()
  val deviceId=Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID)?:Wire.id()
  fun start() { server.start(false); nsd.registerService(NsdServiceInfo().apply { serviceName="AndroidMirror"; serviceType=SERVICE; port=PORT; setAttribute("id",deviceId.take(12)) },NsdManager.PROTOCOL_DNS_SD,registration) }
  fun stop() { active.getAndSet(null)?.let { s->scope.launch { s.close() } }; runCatching { nsd.unregisterService(registration) }; server.stop(500,1500) }
  fun sendSignal(signal:Signal) { active.get()?.let { s->scope.launch { s.send(Frame.Text(Wire.json.encodeToString(Envelope("signal",signal))) } } }
  private suspend fun handle(ws:DefaultWebSocketServerSession) { ws.incoming.consumeEach { f->
    if(f !is Frame.Text) return@consumeEach
    val root=Wire.json.decodeFromString<JsonObject>(f.readText()); val type=root["type"]?.jsonPrimitive?.content?:return@consumeEach; val body=root["body"]?:return@consumeEach
    when(type) {
      "pair-request"->{ val r=Wire.json.decodeFromJsonElement<PairRequest>(body); val ok=r.shortCode==shortCode&&callbacks.pairRequested(r.computerName.take(60),r.clientPublicKey); if(ok) paired[r.clientPublicKey]=r.computerName; ws.send(Frame.Text(Wire.json.encodeToString(Envelope("pair-result",PairResult(ok,deviceId,android.os.Build.MODEL,if(ok)null else "Rejected"))))) }
      "session-request"->{ val r=Wire.json.decodeFromJsonElement<SessionRequest>(body); val ok=paired.containsKey(r.clientId)&&callbacks.sessionRequested(r.clientId); if(ok) active.set(ws); ws.send(Frame.Text(Wire.json.encodeToString(Envelope("session-status",SessionStatus(if(ok)"capture-starting" else "rejected"))))) }
      "signal"->if(active.get()===ws) callbacks.signal(Wire.json.decodeFromJsonElement<Signal>(body))
      "control"->if(active.get()===ws) callbacks.control(Wire.json.decodeFromJsonElement<Control>(body))
    }
  } }
}
