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

class LanSignalingServer(private val context:Context,private val callbacks:Callbacks) {
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
 val shortCode=context.getSharedPreferences("mirror",Context.MODE_PRIVATE).getString("pair_code",null) ?: (100000+SecureRandom().nextInt(900000)).toString()
 val deviceId=Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID)?:Wire.id()
 fun start() { server.start(false); nsd.registerService(NsdServiceInfo().apply { serviceName="AndroidMirror"; serviceType=SERVICE; port=PORT; setAttribute("id",deviceId.take(12)) },NsdManager.PROTOCOL_DNS_SD,registration) }
 fun stop() { active.getAndSet(null)?.let { socket->scope.launch { socket.close() } }; runCatching { nsd.unregisterService(registration) }; server.stop(500,1500) }
 fun sendSignal(signal:Signal) { val socket=active.get()?:return; scope.launch { val message=Envelope<Signal>(type="signal",body=signal); socket.send(Frame.Text(Wire.json.encodeToString(message))) } }
 private suspend fun handle(ws:DefaultWebSocketServerSession) { ws.incoming.consumeEach { frame->
  if(frame !is Frame.Text) return@consumeEach
  val root=Wire.json.decodeFromString<JsonObject>(frame.readText()); val type=root["type"]?.jsonPrimitive?.content?:return@consumeEach; val body=root["body"]?:return@consumeEach
  when(type) {
   "pair-request"->{ val r=Wire.json.decodeFromJsonElement<PairRequest>(body); val ok=r.shortCode==shortCode&&callbacks.pairRequested(r.computerName.take(60),r.clientPublicKey); if(ok) paired[r.clientPublicKey]=r.computerName; val out=Envelope<PairResult>(type="pair-result",body=PairResult(ok,deviceId,android.os.Build.MODEL,if(ok)null else "Rejected")); ws.send(Frame.Text(Wire.json.encodeToString(out))) }
   "session-request"->{ val r=Wire.json.decodeFromJsonElement<SessionRequest>(body); val ok=paired.containsKey(r.clientId)&&callbacks.sessionRequested(r.clientId); if(ok) active.set(ws); val out=Envelope<SessionStatus>(type="session-status",body=SessionStatus(if(ok)"capture-starting" else "rejected")); ws.send(Frame.Text(Wire.json.encodeToString(out))) }
   "signal"->if(active.get()===ws) callbacks.signal(Wire.json.decodeFromJsonElement<Signal>(body))
   "control"->if(active.get()===ws) callbacks.control(Wire.json.decodeFromJsonElement<Control>(body))
  }
 } }
}
