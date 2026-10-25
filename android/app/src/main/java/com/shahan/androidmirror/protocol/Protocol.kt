package com.shahan.androidmirror.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom

object Wire {
    const val VERSION = 1
    const val MAX_BYTES = 65_536
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    fun id(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
@Serializable data class Envelope<T>(val version: Int = Wire.VERSION, val type: String, val sessionId: String? = null, val requestId: String = Wire.id(), val timestampMs: Long = System.currentTimeMillis(), val body: T)
@Serializable data class Hello(val deviceName: String, val clientPublicKey: String? = null)
@Serializable data class PairRequest(val shortCode: String, val computerName: String, val clientPublicKey: String)
@Serializable data class PairResult(val accepted: Boolean, val deviceId: String, val deviceName: String, val reason: String? = null)
@Serializable data class SessionRequest(val mode: String = "view", val clientId: String)
@Serializable data class SessionStatus(val state: String, val message: String? = null)
@Serializable data class Signal(val sdpType: String? = null, val sdp: String? = null, val candidate: String? = null, val sdpMid: String? = null, val sdpMLineIndex: Int? = null)
@Serializable data class Viewport(val width: Int, val height: Int, val rotation: Int, val visibleLeft: Int = 0, val visibleTop: Int = 0)
@Serializable data class Control(val kind: String, val x: Float? = null, val y: Float? = null, val endX: Float? = null, val endY: Float? = null, val durationMs: Long? = null, val text: String? = null, val action: String? = null)
fun validEnvelope(raw: String): Boolean = raw.length <= Wire.MAX_BYTES && raw.isNotBlank()
