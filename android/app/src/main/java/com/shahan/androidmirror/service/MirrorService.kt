package com.shahan.androidmirror.service

import android.app.*
import android.content.*
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.shahan.androidmirror.R
import com.shahan.androidmirror.accessibility.RemoteControlService
import com.shahan.androidmirror.discovery.LanSignalingServer
import com.shahan.androidmirror.protocol.*
import org.webrtc.*
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Owns consent-bound capture. Stops immediately through notification or Android's projection callback. */
class MirrorService : Service(), LanSignalingServer.Callbacks {
    private val egl = EglBase.create(); private val executor = Executors.newSingleThreadExecutor()
    private lateinit var server: LanSignalingServer; private var factory: PeerConnectionFactory? = null; private var peer: PeerConnection? = null; private var capturer: ScreenCapturerAndroid? = null
    private var projectionData: Intent? = null; private var resultCode = 0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }; projectionData = intent?.getParcelableExtra("data"); resultCode = intent?.getIntExtra("result", 0) ?: 0; startForeground(7, notification()); startServer(); return START_NOT_STICKY }
    private fun startServer() { server = LanSignalingServer(this, this); server.start() }
    private fun notification(): Notification { val channel = NotificationChannel("mirror", getString(R.string.mirror_channel), NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager::class.java).createNotificationChannel(channel); val stop = PendingIntent.getService(this, 1, Intent(this, MirrorService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE); return NotificationCompat.Builder(this, "mirror").setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Android Mirror is sharing your screen").setContentText("Tap Stop to end sharing").addAction(0, "Stop", stop).setOngoing(true).build() }
    private fun fingerprint(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    override fun pairRequested(name: String, key: String): Boolean = getSharedPreferences("paired", MODE_PRIVATE).edit().putString(fingerprint(key), name).commit()
    override fun sessionRequested(clientId: String): Boolean = getSharedPreferences("paired", MODE_PRIVATE).contains(fingerprint(clientId))
    override fun signal(signal: Signal) = executor.execute { if (signal.sdpType == "offer") answer(signal) else if (signal.candidate != null) peer?.addIceCandidate(IceCandidate(signal.sdpMid, signal.sdpMLineIndex ?: 0, signal.candidate)) }
    override fun control(control: Control) { val metrics = resources.displayMetrics; if (RemoteControlService.instance == null) { server.sendSignal(Signal()); return }; RemoteControlService.instance?.execute(control, metrics.widthPixels, metrics.heightPixels) }
    private fun answer(offer: Signal) { ensurePeer(); val p = peer ?: return; p.setRemoteDescription(observer {}, SessionDescription(SessionDescription.Type.OFFER, offer.sdp ?: return)); p.createAnswer(object : SdpObserver { override fun onCreateSuccess(s: SessionDescription) { p.setLocalDescription(observer {}, s); server.sendSignal(Signal(s.type.canonicalForm(), s.description)) }; override fun onSetSuccess(){}; override fun onCreateFailure(e:String){}; override fun onSetFailure(e:String){} }, MediaConstraints()) }
    private fun ensurePeer() { if (peer != null || projectionData == null) return; PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()); factory = PeerConnectionFactory.builder().setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)).setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory(); capturer = ScreenCapturerAndroid(projectionData, object: MediaProjection.Callback(){ override fun onStop(){ stopSelf() } }); val source = factory!!.createVideoSource(true); capturer!!.initialize(SurfaceTextureHelper.create("capture", egl.eglBaseContext), this, source.capturerObserver); capturer!!.startCapture(1280, 720, 30); peer = factory!!.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), object: PeerConnection.Observer { override fun onIceCandidate(c: IceCandidate) { server.sendSignal(Signal(candidate=c.sdp, sdpMid=c.sdpMid, sdpMLineIndex=c.sdpMLineIndex)) }; override fun onConnectionChange(s: PeerConnection.PeerConnectionState){}; override fun onSignalingChange(s: PeerConnection.SignalingState){}; override fun onIceConnectionChange(s: PeerConnection.IceConnectionState){}; override fun onIceConnectionReceivingChange(b:Boolean){}; override fun onIceGatheringChange(s:PeerConnection.IceGatheringState){}; override fun onIceCandidatesRemoved(c:Array<IceCandidate>){}; override fun onAddStream(s:MediaStream){}; override fun onRemoveStream(s:MediaStream){}; override fun onDataChannel(c:DataChannel){}; override fun onRenegotiationNeeded(){}; override fun onAddTrack(r:RtpReceiver?, m:Array<out MediaStream>?){} }).also { it?.addTrack(factory!!.createVideoTrack("screen", source), listOf("mirror")) } }
    private fun observer(done: () -> Unit) = object : SdpObserver { override fun onCreateSuccess(s: SessionDescription){}; override fun onSetSuccess(){ done() }; override fun onCreateFailure(e:String){}; override fun onSetFailure(e:String){} }
    override fun onDestroy() { runCatching { capturer?.stopCapture() }; capturer?.dispose(); peer?.dispose(); factory?.dispose(); egl.release(); if (::server.isInitialized) server.stop(); executor.shutdownNow(); super.onDestroy() }
}
