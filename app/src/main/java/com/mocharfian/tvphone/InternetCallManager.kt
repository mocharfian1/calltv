package com.mocharfian.tvphone

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

enum class DevicePlatform(val label: String) {
    TV("TV"),
    Phone("HP"),
}

enum class CallPhase {
    Idle,
    Dialing,
    Incoming,
    Connected,
}

data class SelfDevice(
    val id: String,
    val name: String,
    val code: String,
    val platform: DevicePlatform,
)

data class PeerDevice(
    val id: String,
    val name: String,
    val code: String,
    val platform: DevicePlatform,
)

data class RecentCall(
    val title: String,
    val number: String,
    val status: String,
    val timeLabel: String,
    val durationLabel: String,
    val platformLabel: String,
)

data class CallManagerState(
    val selfDevice: SelfDevice,
    val serverUrl: String = "",
    val isConnected: Boolean = false,
    val discoveredDevices: List<PeerDevice> = emptyList(),
    val activePeer: PeerDevice? = null,
    val incomingPeer: PeerDevice? = null,
    val callPhase: CallPhase = CallPhase.Idle,
    val elapsedSeconds: Int = 0,
    val statusText: String = "Masukkan alamat server internet untuk mulai.",
    val recentCalls: List<RecentCall> = emptyList(),
)

private const val MSG_REGISTER = "register"
private const val MSG_REGISTERED = "registered"
private const val MSG_PEER_LIST = "peer_list"
private const val MSG_INVITE = "invite"
private const val MSG_INCOMING = "incoming"
private const val MSG_ACCEPT = "accept"
private const val MSG_ACCEPTED = "accepted"
private const val MSG_REJECT = "reject"
private const val MSG_REJECTED = "rejected"
private const val MSG_BUSY = "busy"
private const val MSG_END = "end"
private const val MSG_ENDED = "ended"
private const val MSG_ERROR = "error"

private const val AUDIO_FRAME_MARKER: Byte = 0x01
private const val SAMPLE_RATE = 16_000
private const val FRAME_BYTES = 640

class InternetCallManager(
    application: Application,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val settingsRepository = CallSettingsRepository(application)
    private val selfDevice = createSelfDevice(application)
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(
        CallManagerState(
            selfDevice = selfDevice,
            serverUrl = settingsRepository.getServerUrl(),
            statusText = if (settingsRepository.getServerUrl().isBlank()) {
                "Masukkan alamat server internet untuk mulai."
            } else {
                "Menyambung ke server..."
            },
        ),
    )
    val state: StateFlow<CallManagerState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var activeSocketUrl: String? = null
    private var audioSession: AudioStreamSession? = null
    private var timerJob: Job? = null

    init {
        val savedUrl = settingsRepository.getServerUrl()
        if (savedUrl.isNotBlank()) {
            connect(savedUrl)
        }
    }

    fun connect(serverUrl: String) {
        val normalizedUrl = normalizeServerUrl(serverUrl)
        settingsRepository.saveServerUrl(normalizedUrl)

        disconnectInternal(
            reason = "Menyambung ulang ke server...",
            clearPeers = true,
            sendEnd = false,
        )

        if (normalizedUrl.isBlank()) {
            _state.update {
                it.copy(
                    serverUrl = "",
                    isConnected = false,
                    statusText = "Masukkan alamat server internet untuk mulai.",
                )
            }
            return
        }

        activeSocketUrl = normalizedUrl
        _state.update {
            it.copy(
                serverUrl = normalizedUrl,
                isConnected = false,
                discoveredDevices = emptyList(),
                activePeer = null,
                incomingPeer = null,
                callPhase = CallPhase.Idle,
                elapsedSeconds = 0,
                statusText = "Menyambung ke $normalizedUrl ...",
            )
        }

        val request = Request.Builder()
            .url(normalizedUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, SignalSocketListener())
    }

    fun disconnect() {
        disconnectInternal(
            reason = "Koneksi server diputus.",
            clearPeers = true,
            sendEnd = true,
        )
    }

    fun invite(peer: PeerDevice) {
        val currentState = _state.value
        if (!currentState.isConnected || currentState.callPhase != CallPhase.Idle) return

        sendJson(
            JSONObject()
                .put("type", MSG_INVITE)
                .put("toId", peer.id),
        )

        _state.update {
            it.copy(
                activePeer = peer,
                incomingPeer = null,
                callPhase = CallPhase.Dialing,
                elapsedSeconds = 0,
                statusText = "Memanggil ${peer.name} (${peer.code}) lewat internet...",
            )
        }
    }

    fun acceptIncoming() {
        val peer = _state.value.incomingPeer ?: return
        if (_state.value.callPhase != CallPhase.Incoming) return

        try {
            startAudioStreaming()
            sendJson(
                JSONObject()
                    .put("type", MSG_ACCEPT)
                    .put("toId", peer.id),
            )
            startTimer()
            _state.update {
                it.copy(
                    activePeer = peer,
                    incomingPeer = null,
                    callPhase = CallPhase.Connected,
                    elapsedSeconds = 0,
                    statusText = "Panggilan dengan ${peer.name} tersambung.",
                )
            }
        } catch (error: Exception) {
            stopAudioStreaming()
            _state.update {
                it.copy(
                    incomingPeer = null,
                    activePeer = null,
                    callPhase = CallPhase.Idle,
                    statusText = "Audio tidak bisa dimulai: ${error.userMessage()}",
                )
            }
            sendJson(
                JSONObject()
                    .put("type", MSG_REJECT)
                    .put("toId", peer.id),
            )
        }
    }

    fun rejectIncoming() {
        val peer = _state.value.incomingPeer ?: return

        sendJson(
            JSONObject()
                .put("type", MSG_REJECT)
                .put("toId", peer.id),
        )

        finishCall(
            peer = peer,
            result = "Ditolak",
            message = "Panggilan dari ${peer.name} ditolak.",
            elapsedSeconds = 0,
        )
    }

    fun endCall() {
        val peer = _state.value.activePeer ?: _state.value.incomingPeer ?: return
        if (_state.value.callPhase == CallPhase.Idle) return

        sendJson(
            JSONObject()
                .put("type", MSG_END)
                .put("toId", peer.id),
        )

        val result = when (_state.value.callPhase) {
            CallPhase.Idle -> "Batal"
            CallPhase.Dialing -> "Dibatalkan"
            CallPhase.Incoming -> "Ditolak"
            CallPhase.Connected -> "Selesai"
        }

        finishCall(
            peer = peer,
            result = result,
            message = "Panggilan berakhir.",
            elapsedSeconds = _state.value.elapsedSeconds,
        )
    }

    fun shutdown() {
        disconnectInternal(
            reason = "Menutup layanan panggilan...",
            clearPeers = true,
            sendEnd = true,
        )
        okHttpClient.dispatcher.executorService.shutdown()
    }

    private fun disconnectInternal(
        reason: String,
        clearPeers: Boolean,
        sendEnd: Boolean,
    ) {
        val currentPeer = _state.value.activePeer ?: _state.value.incomingPeer
        if (sendEnd && currentPeer != null && _state.value.callPhase != CallPhase.Idle) {
            sendJson(
                JSONObject()
                    .put("type", MSG_END)
                    .put("toId", currentPeer.id),
            )
        }

        stopTimer()
        stopAudioStreaming()
        webSocket?.close(1000, "closing")
        webSocket = null
        activeSocketUrl = null

        _state.update {
            it.copy(
                isConnected = false,
                discoveredDevices = if (clearPeers) emptyList() else it.discoveredDevices,
                activePeer = null,
                incomingPeer = null,
                callPhase = CallPhase.Idle,
                elapsedSeconds = 0,
                statusText = reason,
            )
        }
    }

    private fun handleTextMessage(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return

        when (json.optString("type")) {
            MSG_REGISTERED -> {
                _state.update {
                    it.copy(
                        isConnected = true,
                        statusText = "Terhubung ke server. Menunggu perangkat online lainnya.",
                    )
                }
            }

            MSG_PEER_LIST -> {
                val peers = json.optJSONArray("peers").toPeerList()
                _state.update { current ->
                    current.copy(
                        discoveredDevices = peers,
                        statusText = if (current.callPhase == CallPhase.Idle) {
                            if (peers.isEmpty()) {
                                "Server aktif, belum ada perangkat online lain."
                            } else {
                                "Ditemukan ${peers.size} perangkat online. Masukkan kode tujuan untuk menelepon."
                            }
                        } else {
                            current.statusText
                        },
                    )
                }
            }

            MSG_INCOMING -> {
                val peer = json.optJSONObject("from")?.toPeerDevice() ?: return
                if (_state.value.callPhase != CallPhase.Idle) {
                    sendJson(
                        JSONObject()
                            .put("type", MSG_BUSY)
                            .put("toId", peer.id),
                    )
                    return
                }

                _state.update {
                    it.copy(
                        activePeer = peer,
                        incomingPeer = peer,
                        callPhase = CallPhase.Incoming,
                        elapsedSeconds = 0,
                        statusText = "${peer.name} (${peer.code}) sedang memanggil lewat internet.",
                    )
                }
            }

            MSG_ACCEPTED -> {
                val peer = json.optJSONObject("peer")?.toPeerDevice() ?: _state.value.activePeer ?: return
                if (_state.value.callPhase != CallPhase.Dialing) return
                try {
                    startAudioStreaming()
                    startTimer()
                    _state.update {
                        it.copy(
                            activePeer = peer,
                            incomingPeer = null,
                            callPhase = CallPhase.Connected,
                            elapsedSeconds = 0,
                            statusText = "Panggilan dengan ${peer.name} tersambung.",
                        )
                    }
                } catch (error: Exception) {
                    sendJson(
                        JSONObject()
                            .put("type", MSG_END)
                            .put("toId", peer.id),
                    )
                    finishCall(
                        peer = peer,
                        result = "Gagal",
                        message = "Audio tidak bisa dimulai: ${error.userMessage()}",
                        elapsedSeconds = 0,
                    )
                }
            }

            MSG_REJECTED -> {
                val peer = findPeerById(json.optString("byId"))
                finishCall(
                    peer = peer,
                    result = "Ditolak",
                    message = "${peer?.name ?: "Perangkat tujuan"} menolak panggilan.",
                    elapsedSeconds = 0,
                )
            }

            MSG_BUSY -> {
                val peer = findPeerById(json.optString("byId"))
                finishCall(
                    peer = peer,
                    result = "Sibuk",
                    message = "${peer?.name ?: "Perangkat tujuan"} sedang sibuk.",
                    elapsedSeconds = 0,
                )
            }

            MSG_ENDED -> {
                val peer = findPeerById(json.optString("byId"))
                val messageText = when (_state.value.callPhase) {
                    CallPhase.Dialing -> "${peer?.name ?: "Perangkat tujuan"} membatalkan atau tidak melanjutkan panggilan."
                    CallPhase.Incoming -> "${peer?.name ?: "Pemanggil"} membatalkan panggilan."
                    CallPhase.Connected -> "Panggilan dengan ${peer?.name ?: "perangkat tujuan"} berakhir."
                    CallPhase.Idle -> "Panggilan selesai."
                }
                finishCall(
                    peer = peer,
                    result = if (_state.value.callPhase == CallPhase.Connected) "Selesai" else "Dibatalkan",
                    message = messageText,
                    elapsedSeconds = _state.value.elapsedSeconds,
                )
            }

            MSG_ERROR -> {
                val messageText = json.optString("message").ifBlank { "Terjadi kesalahan dari server." }
                val peer = _state.value.activePeer ?: _state.value.incomingPeer
                finishCall(
                    peer = peer,
                    result = "Gagal",
                    message = messageText,
                    elapsedSeconds = 0,
                )
            }
        }
    }

    private fun handleBinaryMessage(bytes: ByteString) {
        if (_state.value.callPhase != CallPhase.Connected) return
        val payload = bytes.toByteArray()
        if (payload.isEmpty() || payload.first() != AUDIO_FRAME_MARKER) return
        val audioBytes = payload.copyOfRange(1, payload.size)
        if (audioBytes.isNotEmpty()) {
            audioSession?.queueRemoteAudio(audioBytes)
        }
    }

    private fun startAudioStreaming() {
        stopAudioStreaming()
        audioSession = AudioStreamSession { frame ->
            sendAudioFrame(frame)
        }.also { session ->
            session.start(scope)
        }
    }

    private fun stopAudioStreaming() {
        audioSession?.stop()
        audioSession = null
    }

    private fun sendAudioFrame(frame: ByteArray) {
        val packet = ByteArray(frame.size + 1)
        packet[0] = AUDIO_FRAME_MARKER
        frame.copyInto(packet, destinationOffset = 1)
        webSocket?.send(packet.toByteString())
    }

    private fun sendJson(payload: JSONObject) {
        webSocket?.send(payload.toString())
    }

    private fun startTimer() {
        stopTimer()
        timerJob = scope.launch {
            while (isActive) {
                delay(1_000)
                _state.update { current ->
                    if (current.callPhase == CallPhase.Connected) {
                        current.copy(elapsedSeconds = current.elapsedSeconds + 1)
                    } else {
                        current
                    }
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun finishCall(
        peer: PeerDevice?,
        result: String,
        message: String,
        elapsedSeconds: Int,
    ) {
        stopTimer()
        stopAudioStreaming()

        _state.update { current ->
            current.copy(
                activePeer = null,
                incomingPeer = null,
                callPhase = CallPhase.Idle,
                elapsedSeconds = 0,
                statusText = message,
                recentCalls = peer?.let {
                    listOf(
                        RecentCall(
                            title = it.name,
                            number = it.code,
                            status = result,
                            timeLabel = currentTimeLabel(),
                            durationLabel = if (elapsedSeconds > 0) formatElapsed(elapsedSeconds) else "--:--",
                            platformLabel = it.platform.label,
                        ),
                    ) + current.recentCalls.take(9)
                } ?: current.recentCalls,
            )
        }
    }

    private fun findPeerById(id: String?): PeerDevice? {
        if (id.isNullOrBlank()) return _state.value.activePeer ?: _state.value.incomingPeer
        return _state.value.discoveredDevices.firstOrNull { it.id == id }
            ?: _state.value.activePeer?.takeIf { it.id == id }
            ?: _state.value.incomingPeer?.takeIf { it.id == id }
    }

    private inner class SignalSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val registerPayload = JSONObject()
                .put("type", MSG_REGISTER)
                .put("deviceId", selfDevice.id)
                .put("deviceName", selfDevice.name)
                .put("deviceCode", selfDevice.code)
                .put("platform", selfDevice.platform.name.lowercase(Locale.ROOT))

            webSocket.send(registerPayload.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch {
                handleTextMessage(text)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            scope.launch(Dispatchers.IO) {
                handleBinaryMessage(bytes)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch {
                val activeUrl = activeSocketUrl
                stopTimer()
                stopAudioStreaming()
                _state.update {
                    it.copy(
                        isConnected = false,
                        discoveredDevices = emptyList(),
                        activePeer = null,
                        incomingPeer = null,
                        callPhase = CallPhase.Idle,
                        elapsedSeconds = 0,
                        statusText = if (activeUrl.isNullOrBlank()) {
                            "Koneksi server ditutup."
                        } else {
                            "Koneksi ke server terputus. Hubungkan ulang jika ingin menelepon."
                        },
                    )
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch {
                stopTimer()
                stopAudioStreaming()
                _state.update {
                    it.copy(
                        isConnected = false,
                        discoveredDevices = emptyList(),
                        activePeer = null,
                        incomingPeer = null,
                        callPhase = CallPhase.Idle,
                        elapsedSeconds = 0,
                        statusText = "Tidak bisa terhubung ke server: ${t.userMessage()}",
                    )
                }
            }
        }
    }
}

private class AudioStreamSession(
    private val onAudioCaptured: (ByteArray) -> Unit,
) {
    private val playbackQueue = Channel<ByteArray>(
        capacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var audioManager: AudioManager? = null
    private var originalMode: Int = AudioManager.MODE_NORMAL
    private var originalSpeakerState: Boolean = true
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var senderJob: Job? = null
    private var receiverJob: Job? = null

    fun start(scope: CoroutineScope) {
        val context = AppContextHolder.appContext
        val manager = context.getSystemService(AudioManager::class.java)
        audioManager = manager
        originalMode = manager.mode
        originalSpeakerState = manager.isSpeakerphoneOn

        val recordBufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            FRAME_BYTES * 4,
        )
        val trackBufferSize = maxOf(
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            FRAME_BYTES * 4,
        )

        val record = createAudioRecord(recordBufferSize)
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            trackBufferSize,
            AudioTrack.MODE_STREAM,
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            track.release()
            throw IllegalStateException("AudioRecord gagal diinisialisasi.")
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            record.release()
            track.release()
            throw IllegalStateException("AudioTrack gagal diinisialisasi.")
        }

        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.isSpeakerphoneOn = true

        record.startRecording()
        track.play()

        audioRecord = record
        audioTrack = track

        senderJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(FRAME_BYTES)
            while (isActive) {
                val bytesRead = record.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    onAudioCaptured(buffer.copyOf(bytesRead))
                }
            }
        }

        receiverJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val frame = playbackQueue.receive()
                track.write(frame, 0, frame.size)
            }
        }
    }

    fun queueRemoteAudio(frame: ByteArray) {
        if (!playbackQueue.isClosedForSend) {
            playbackQueue.trySend(frame.copyOf())
        }
    }

    fun stop() {
        senderJob?.cancel()
        receiverJob?.cancel()
        senderJob = null
        receiverJob = null

        playbackQueue.close()

        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioTrack?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
        audioTrack = null

        audioManager?.let {
            it.mode = originalMode
            it.isSpeakerphoneOn = originalSpeakerState
        }
        audioManager = null
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord {
        val preferred = AudioRecord(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            },
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (preferred.state == AudioRecord.STATE_INITIALIZED) {
            return preferred
        }

        preferred.release()
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
    }
}

private object AppContextHolder {
    lateinit var appContext: Context
}

private fun normalizeServerUrl(serverUrl: String): String {
    val trimmed = serverUrl.trim()
    if (trimmed.isBlank()) return ""
    return if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
        trimmed
    } else {
        "ws://$trimmed"
    }
}

private fun createSelfDevice(context: Context): SelfDevice {
    AppContextHolder.appContext = context.applicationContext

    val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?: "unknown-${Build.MODEL}"
    val code = ((androidId.hashCode().absoluteValue % 900000) + 100000).toString()
    val fallbackName = if (isTv) "Android TV" else "Android HP"
    val readableName = Build.MODEL?.trim().orEmpty().ifBlank { fallbackName }

    return SelfDevice(
        id = androidId,
        name = readableName,
        code = code,
        platform = if (isTv) DevicePlatform.TV else DevicePlatform.Phone,
    )
}

private fun JSONArray?.toPeerList(): List<PeerDevice> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.toPeerDevice()?.let(::add)
        }
    }.sortedWith(compareBy<PeerDevice> { it.code }.thenBy { it.name })
}

private fun JSONObject.toPeerDevice(): PeerDevice? {
    val id = optString("id")
    val name = optString("name")
    val code = optString("code")
    if (id.isBlank() || name.isBlank() || code.isBlank()) return null

    return PeerDevice(
        id = id,
        name = name,
        code = code,
        platform = optString("platform").toPlatform(),
    )
}

private fun String.toPlatform(): DevicePlatform = when (lowercase(Locale.ROOT)) {
    "tv" -> DevicePlatform.TV
    else -> DevicePlatform.Phone
}

private fun currentTimeLabel(): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date())
}

fun formatElapsed(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val minutes = safeSeconds / 60
    val remainingSeconds = safeSeconds % 60
    return "%02d:%02d".format(minutes, remainingSeconds)
}

private fun Throwable.userMessage(): String = message ?: javaClass.simpleName

