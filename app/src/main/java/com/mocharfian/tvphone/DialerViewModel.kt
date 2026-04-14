package com.mocharfian.tvphone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DialerUiState(
    val selfDevice: SelfDevice = SelfDevice(
        id = "",
        name = "Perangkat Android",
        code = "000000",
        platform = DevicePlatform.Phone,
    ),
    val serverUrlInput: String = "",
    val savedServerUrl: String = "",
    val isConnected: Boolean = false,
    val enteredNumber: String = "",
    val activePeer: PeerDevice? = null,
    val incomingPeer: PeerDevice? = null,
    val callPhase: CallPhase = CallPhase.Idle,
    val elapsedSeconds: Int = 0,
    val helperText: String = "Menyalakan layanan panggilan lokal...",
    val discoveredDevices: List<PeerDevice> = emptyList(),
    val recentCalls: List<RecentCall> = emptyList(),
    val isAudioPermissionGranted: Boolean = false,
)

class DialerViewModel(application: Application) : AndroidViewModel(application) {
    private val callManager = InternetCallManager(application, viewModelScope)
    private val _uiState = MutableStateFlow(
        DialerUiState(
            selfDevice = callManager.state.value.selfDevice,
            serverUrlInput = callManager.state.value.serverUrl,
            savedServerUrl = callManager.state.value.serverUrl,
        ),
    )
    val uiState: StateFlow<DialerUiState> = _uiState.asStateFlow()

    private var idleHelperOverride: String? = null

    init {
        viewModelScope.launch {
            callManager.state.collect { managerState ->
                if (managerState.callPhase != CallPhase.Idle) {
                    idleHelperOverride = null
                }

                _uiState.update { current ->
                    current.copy(
                        selfDevice = managerState.selfDevice,
                        serverUrlInput = if (managerState.serverUrl != current.savedServerUrl) {
                            managerState.serverUrl
                        } else {
                            current.serverUrlInput
                        },
                        savedServerUrl = managerState.serverUrl,
                        isConnected = managerState.isConnected,
                        activePeer = managerState.activePeer,
                        incomingPeer = managerState.incomingPeer,
                        callPhase = managerState.callPhase,
                        elapsedSeconds = managerState.elapsedSeconds,
                        discoveredDevices = managerState.discoveredDevices,
                        recentCalls = managerState.recentCalls,
                        helperText = if (managerState.callPhase == CallPhase.Idle) {
                            idleHelperOverride ?: managerState.statusText
                        } else {
                            managerState.statusText
                        },
                    )
                }
            }
        }
    }

    fun appendDigit(digit: String) {
        val state = _uiState.value
        if (state.callPhase != CallPhase.Idle) return
        if (state.enteredNumber.length >= 6) return

        _uiState.update { it.copy(enteredNumber = it.enteredNumber + digit) }
        updateIdleHelper("Masukkan kode perangkat 6 digit tujuan, lalu tekan Call.")
    }

    fun deleteLastDigit() {
        val state = _uiState.value
        if (state.callPhase != CallPhase.Idle || state.enteredNumber.isEmpty()) return

        val nextValue = state.enteredNumber.dropLast(1)
        _uiState.update { it.copy(enteredNumber = nextValue) }
        updateIdleHelper(
            if (nextValue.isBlank()) {
                "Masukkan kode perangkat tujuan."
            } else {
                "Kode siap dipanggil."
            },
        )
    }

    fun clearNumber() {
        if (_uiState.value.callPhase != CallPhase.Idle) return
        _uiState.update { it.copy(enteredNumber = "") }
        updateIdleHelper("Masukkan kode perangkat tujuan.")
    }

    fun reuseNumber(number: String) {
        if (_uiState.value.callPhase != CallPhase.Idle) return
        _uiState.update { it.copy(enteredNumber = number) }
        updateIdleHelper("Kode diambil dari daftar perangkat. Tekan Call untuk menelepon.")
    }

    fun startCall() {
        val state = _uiState.value
        if (state.callPhase != CallPhase.Idle) return

        if (!state.isConnected) {
            updateIdleHelper("Hubungkan aplikasi ke server internet terlebih dahulu.")
            return
        }

        if (!state.isAudioPermissionGranted) {
            updateIdleHelper("Izinkan akses mikrofon agar panggilan audio bisa berjalan.")
            return
        }

        val code = state.enteredNumber.trim()
        if (code.isBlank()) {
            updateIdleHelper("Masukkan kode perangkat tujuan.")
            return
        }

        val peer = callManager.state.value.discoveredDevices.firstOrNull { it.code == code }
        if (peer == null) {
            updateIdleHelper("Perangkat dengan kode $code belum online di server.")
            return
        }

        callManager.invite(peer)
    }

    fun acceptIncoming() {
        if (!_uiState.value.isAudioPermissionGranted) {
            updateIdleHelper("Izinkan akses mikrofon agar panggilan bisa dijawab.")
            return
        }
        callManager.acceptIncoming()
    }

    fun rejectIncoming() {
        callManager.rejectIncoming()
    }

    fun endCall() {
        callManager.endCall()
    }

    fun onAudioPermissionChanged(granted: Boolean) {
        _uiState.update { it.copy(isAudioPermissionGranted = granted) }
        if (granted && _uiState.value.callPhase == CallPhase.Idle && idleHelperOverride?.contains("mikrofon") == true) {
            idleHelperOverride = null
            _uiState.update {
                it.copy(helperText = callManager.state.value.statusText)
            }
        }
    }

    fun updateServerUrlInput(value: String) {
        _uiState.update { it.copy(serverUrlInput = value) }
    }

    fun connectToServer() {
        val input = _uiState.value.serverUrlInput.trim()
        if (input.isBlank()) {
            updateIdleHelper("Masukkan alamat server dulu, misalnya ws://IP-SERVER:8080")
            return
        }
        idleHelperOverride = null
        callManager.connect(input)
    }

    fun disconnectFromServer() {
        idleHelperOverride = null
        callManager.disconnect()
    }

    override fun onCleared() {
        callManager.shutdown()
        super.onCleared()
    }

    private fun updateIdleHelper(message: String) {
        idleHelperOverride = message
        if (_uiState.value.callPhase == CallPhase.Idle) {
            _uiState.update { it.copy(helperText = message) }
        }
    }
}
