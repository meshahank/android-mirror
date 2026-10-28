package com.shahan.androidmirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.shahan.androidmirror.R
import com.shahan.androidmirror.accessibility.RemoteControlService
import com.shahan.androidmirror.discovery.LanSignalingServer
import com.shahan.androidmirror.protocol.Control
import com.shahan.androidmirror.protocol.Signal
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import java.security.MessageDigest
import java.util.concurrent.Executors

class MirrorService : Service(), LanSignalingServer.Callbacks {
    private val egl = EglBase.create()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var server: LanSignalingServer
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var projectionData: Intent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setStage("Sharing stopped on this phone")
            stopSelf()
            return START_NOT_STICKY
        }
        projectionData = intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
        if (projectionData == null) {
            setStage("ERROR: Android did not provide screen-share permission")
            stopSelf()
            return START_NOT_STICKY
        }
        setStage("Ready: waiting for the desktop to connect")
        startForeground(NOTIFICATION_ID, notification())
        server = LanSignalingServer(this, this)
        server.start()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.mirror_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Android Mirror is sharing your screen")
            .setContentText("Tap Stop to end sharing")
            .addAction(0, "Stop", stopIntent).setOngoing(true).build()
    }

    private fun setStage(value: String) {
        getSharedPreferences("mirror", MODE_PRIVATE).edit().putString("session_stage", value).apply()
    }

    private fun fingerprint(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    override fun pairRequested(name: String, key: String): Boolean = getSharedPreferences("paired", MODE_PRIVATE).edit().putString(fingerprint(key), name).commit()
    override fun sessionRequested(clientId: String): Boolean = getSharedPreferences("paired", MODE_PRIVATE).contains(fingerprint(clientId))

    override fun signal(signal: Signal) {
        executor.execute {
            try {
                if (signal.sdpType == "offer") {
                    setStage("Desktop offer received: starting video capture")
                    answer(signal)
                } else if (signal.candidate != null) {
                    peer?.addIceCandidate(IceCandidate(signal.sdpMid, signal.sdpMLineIndex ?: 0, signal.candidate))
                }
            } catch (error: Throwable) {
                report("Screen capture setup failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    override fun control(control: Control) {
        val metrics = resources.displayMetrics
        RemoteControlService.instance?.execute(control, metrics.widthPixels, metrics.heightPixels)
    }

    private fun report(message: String) {
        setStage("ERROR: $message")
        if (::server.isInitialized) server.sendStatus("error", message)
    }

    private fun answer(offer: Signal) {
        ensurePeer()
        val connection = peer ?: run { report("Could not create the video connection"); return }
        val description = offer.sdp ?: run { report("Desktop sent an empty video offer"); return }
        setStage("Video capture started: negotiating with desktop")
        connection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { createAnswer(connection) }
            override fun onSetFailure(error: String) { report("Desktop offer rejected: $error") }
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = Unit
        }, SessionDescription(SessionDescription.Type.OFFER, description))
    }

    private fun createAnswer(connection: PeerConnection) {
        connection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(answer: SessionDescription) {
                connection.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        setStage("Answer sent: connecting video")
                        server.sendSignal(Signal(sdpType = answer.type.canonicalForm(), sdp = answer.description))
                    }
                    override fun onSetFailure(error: String) { report("Could not apply video answer: $error") }
                    override fun onCreateSuccess(description: SessionDescription) = Unit
                    override fun onCreateFailure(error: String) = Unit
                }, answer)
            }
            override fun onCreateFailure(error: String) { report("Could not create video answer: $error") }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String) = Unit
        }, MediaConstraints())
    }

    private fun ensurePeer() {
        if (peer != null) return
        val data = projectionData ?: throw IllegalStateException("Screen-share permission is missing")
        setStage("Creating Android video engine")
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
        factory = PeerConnectionFactory.builder().setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)).setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
        capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                report("Android ended the screen-capture permission")
                mainHandler.postDelayed({ stopSelf() }, 500)
            }
        })
        val source = factory!!.createVideoSource(true)
        capturer!!.initialize(SurfaceTextureHelper.create("capture", egl.eglBaseContext), this, source.capturerObserver)
        capturer!!.startCapture(1280, 720, 30)
        peer = factory!!.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) { server.sendSignal(Signal(candidate = candidate.sdp, sdpMid = candidate.sdpMid, sdpMLineIndex = candidate.sdpMLineIndex)) }
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) = Unit
        })
        peer?.addTrack(factory!!.createVideoTrack("screen", source), listOf("mirror"))
    }

    override fun onDestroy() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); peer?.dispose(); factory?.dispose(); egl.release()
        if (::server.isInitialized) server.stop()
        executor.shutdownNow(); super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_PROJECTION_DATA = "data"
        private const val CHANNEL_ID = "mirror"
        private const val NOTIFICATION_ID = 7
    }
}
