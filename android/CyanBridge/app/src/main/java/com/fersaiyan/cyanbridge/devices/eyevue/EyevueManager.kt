package com.fersaiyan.cyanbridge.devices.eyevue

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

data class EyevueState(
    val connectionLabel: String = "Disconnected",
    val protocolState: String = EyevueGattState.DISCONNECTED.name,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val storageCount: Int? = null,
    val wifiSsid: String? = null,
    val customer: String? = null,
    val project: String? = null,
    val isVideoRecording: Boolean = false,
    val isAudioRecording: Boolean = false,
    val aiWakeWordEnabled: Boolean? = null,
    val localOfflineSpeechEnabled: Boolean? = null,
    val lastError: String? = null,
)

/** Owns Eyevue's native BLE connection and exposes vendor commands to the app. */
class EyevueManager private constructor(context: Context) {
    companion object {
        private const val TAG = "EyevueManager"

        @Volatile
        private var instance: EyevueManager? = null

        fun getInstance(context: Context): EyevueManager =
            instance ?: synchronized(this) {
                instance ?: EyevueManager(context.applicationContext).also { instance = it }
            }
    }

    private val client = EyevueGattClient(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(EyevueState())
    private val _wakeWordEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var connectJob: Job? = null
    private val photoCaptureMutex = Mutex()

    @Volatile
    private var photoPullProbeActive = false

    val state: StateFlow<EyevueState> = _state.asStateFlow()
    val wakeWordEvents: SharedFlow<Unit> = _wakeWordEvents.asSharedFlow()

    init {
        scope.launch {
            client.state.collect { gattState ->
                val current = _state.value
                val label = when (gattState) {
                    EyevueGattState.DISCONNECTED -> "Eyevue disconnected"
                    EyevueGattState.CONNECTING -> "Connecting to Eyevue"
                    EyevueGattState.CONNECTED -> "Eyevue connected"
                    EyevueGattState.ERROR -> current.lastError ?: "Eyevue connection failed"
                }
                _state.value = current.copy(
                    connectionLabel = label,
                    protocolState = gattState.name,
                )
            }
        }
        scope.launch {
            client.frames.collect(::handleFrame)
        }
    }

    fun connect(address: String, deviceName: String? = null) {
        val normalized = address.trim()
        if (normalized.isBlank()) {
            updateError("No Eyevue Bluetooth address was selected")
            return
        }
        connectJob?.cancel()
        connectJob = scope.launch {
            _state.value = EyevueState(
                connectionLabel = "Connecting to Eyevue",
                protocolState = EyevueGattState.CONNECTING.name,
                deviceAddress = normalized,
                deviceName = deviceName,
            )
            val result = client.connect(normalized)
            if (result.isFailure) {
                updateError(result.exceptionOrNull()?.message ?: "Eyevue connection failed")
                return@launch
            }

            _state.value = _state.value.copy(
                connectionLabel = "Eyevue connected",
                protocolState = EyevueGattState.CONNECTED.name,
                lastError = null,
            )
            // Match the vendor startup burst without sending any Oudmon command.
            sendNow(EyevueProtocol.buildGetCustomerPacket(), "get customer")
            sendNow(EyevueProtocol.buildGetBatteryPacket(), "get battery")
            sendNow(EyevueProtocol.buildGetCapacityPacket(), "get capacity")
            sendNow(EyevueProtocol.buildGetVoiceAssistantStatusPacket(), "get AI wake-word status")
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        client.disconnect()
        _state.value = _state.value.copy(
            connectionLabel = "Eyevue disconnected",
            protocolState = EyevueGattState.DISCONNECTED.name,
            isVideoRecording = false,
            isAudioRecording = false,
        )
    }

    fun isConnected(): Boolean = client.isConnected()

    fun takePhoto(highQuality: Boolean = true) = send(
        EyevueProtocol.buildPhotoPacket(highQuality),
        "take photo",
    )

    suspend fun capturePhotoForAi(timeoutMs: Long = 12_000L): ByteArray? = awaitPhoto(
        packet = EyevueProtocol.buildPhotoPacket(highQuality = true),
        timeoutMs = timeoutMs,
        operation = "take photo",
    )

    /** Baseline shutter measurement with the same wake isolation as image-pull probes. */
    suspend fun probeCapturePhoto(timeoutMs: Long = 120_000L): ByteArray? {
        require(timeoutMs in 1L..120_000L) { "Eyevue probe timeout must be between 1 and 120000 ms" }
        return awaitPhoto(
            packet = EyevueProtocol.buildPhotoPacket(highQuality = true),
            timeoutMs = timeoutMs,
            operation = "photo probe shutter=0x31",
            suppressWakeWord = true,
        )
    }

    /** One bounded vendor image-pull request; does not change camera or audio settings. */
    suspend fun probePhotoPull(type: Int, timeoutMs: Long = 120_000L): ByteArray? {
        require(timeoutMs in 1L..120_000L) { "Eyevue photo-pull timeout must be between 1 and 120000 ms" }
        return awaitPhoto(
            packet = EyevueProtocol.buildPullImagePacket(type),
            timeoutMs = timeoutMs,
            operation = "photo-pull probe type=$type",
            suppressWakeWord = true,
        )
    }

    private suspend fun awaitPhoto(
        packet: ByteArray,
        timeoutMs: Long,
        operation: String,
        suppressWakeWord: Boolean = false,
    ): ByteArray? = coroutineScope {
        if (!photoCaptureMutex.tryLock()) {
            throw IOException("An Eyevue photo capture or pull probe is already active")
        }
        try {
            photoPullProbeActive = suppressWakeWord
            client.setPhotoProbeWakeSuppression(suppressWakeWord)
            // Arm the result listener before writing so even an immediate notification
            // is received. Failed writes and rejected transfers propagate to the caller.
            val photo = async(start = CoroutineStart.UNDISPATCHED) { client.photoResults.first() }
            try {
                withTimeoutOrNull(timeoutMs) {
                    client.write(packet).getOrThrow()
                    Log.d(TAG, "Eyevue command sent: $operation")
                    photo.await().getOrThrow()
                }
            } finally {
                photo.cancel()
            }
        } finally {
            client.setPhotoProbeWakeSuppression(false)
            photoPullProbeActive = false
            photoCaptureMutex.unlock()
        }
    }

    fun stopVoiceRecognition() = send(
        EyevueProtocol.buildStopVoiceRecognitionPacket(),
        "stop voice recognition",
    )

    fun toggleVideo() {
        val start = !_state.value.isVideoRecording
        _state.value = _state.value.copy(isVideoRecording = start)
        send(
            if (start) EyevueProtocol.buildStartVideoPacket() else EyevueProtocol.buildStopVideoPacket(),
            if (start) "start video" else "stop video",
        )
    }

    fun toggleAudio() {
        val start = !_state.value.isAudioRecording
        _state.value = _state.value.copy(isAudioRecording = start)
        send(
            EyevueProtocol.buildAudioPacket(start),
            if (start) "start audio" else "stop audio",
        )
    }

    fun requestBattery() = send(EyevueProtocol.buildGetBatteryPacket(), "get battery")

    fun requestStorage() = send(EyevueProtocol.buildGetCapacityPacket(), "get capacity")

    fun requestMediaCount() = send(
        EyevueProtocol.valuePacket(EyevueProtocol.CMD_GET_MEDIA_COUNT, 0),
        "get media count",
    )

    fun requestDeviceInfo() = send(EyevueProtocol.buildGetDeviceInfoPacket(), "get device info")

    fun requestSupportFunction() = send(
        EyevueProtocol.valuePacket(EyevueProtocol.CMD_GET_SUPPORT_FUNCTION, 0),
        "get supported functions",
    )

    fun requestWifiInfo(p2p: Boolean) = send(
        EyevueProtocol.buildGetWifiInfoPacket(p2p),
        "get Wi-Fi information",
    )

    suspend fun awaitWifiSsid(p2p: Boolean, timeoutMs: Long = 15_000L): String? {
        _state.value = _state.value.copy(wifiSsid = null)
        requestWifiInfo(p2p)
        return withTimeoutOrNull(timeoutMs) {
            state
                .filter { !it.wifiSsid.isNullOrBlank() }
                .first()
                .wifiSsid
        }
    }

    suspend fun awaitProject(timeoutMs: Long = 5_000L): String? {
        if (!_state.value.project.isNullOrBlank()) return _state.value.project
        send(EyevueProtocol.buildGetCustomerPacket(), "get customer")
        return withTimeoutOrNull(timeoutMs) {
            state
                .filter { !it.project.isNullOrBlank() }
                .first()
                .project
        }
    }

    fun startLive(ap: Boolean) {
        send(
            if (ap) EyevueProtocol.buildStartLiveApPacket() else EyevueProtocol.buildStartLiveP2pPacket(),
            "start live",
        )
        requestWifiInfo(p2p = !ap)
    }

    fun stopLive() = send(EyevueProtocol.buildFinishTransferPacket(), "stop live")

    fun syncTime() = send(EyevueProtocol.buildSetTimePacket(), "sync time")

    fun requestVolume() = send(
        EyevueProtocol.valuePacket(EyevueProtocol.CMD_GET_VOLUME, 0),
        "get volume",
    )

    fun setWearingDetection(enabled: Boolean) = send(
        EyevueProtocol.buildWearDetectionPacket(enabled),
        "set wearing detection",
    )

    fun setRecordingDuration(seconds: Int) = send(
        EyevueProtocol.buildRecordDurationPacket(seconds),
        "set recording duration",
    )

    fun finishTransfer() = send(EyevueProtocol.buildFinishTransferPacket(), "finish transfer")

    private fun send(packet: ByteArray, operation: String) {
        scope.launch { sendNow(packet, operation) }
    }

    private suspend fun sendNow(packet: ByteArray, operation: String) {
        val result = client.write(packet)
        if (result.isFailure) {
            val error = result.exceptionOrNull() ?: IOException("Unknown Eyevue write failure")
            Log.w(TAG, "Eyevue $operation failed: ${error.message}")
            updateError("Could not $operation: ${error.message}")
        } else {
            Log.d(TAG, "Eyevue command sent: $operation")
        }
    }

    private fun handleFrame(frame: EyevueFrame) {
        EyevueProtocol.parseBattery(frame)?.let { battery ->
            _state.value = _state.value.copy(
                batteryPercent = battery.percent.coerceIn(0, 100),
                isCharging = battery.isCharging,
                lastError = null,
            )
        }
        EyevueProtocol.parseWifiSsid(frame)?.let { ssid ->
            _state.value = _state.value.copy(wifiSsid = ssid, lastError = null)
        }
        EyevueProtocol.parseCustomer(frame)?.let { customer ->
            _state.value = _state.value.copy(
                customer = customer.customer,
                project = customer.project,
                lastError = null,
            )
        }
        EyevueProtocol.parseVoiceAssistantStatus(frame)?.let { status ->
            _state.value = _state.value.copy(
                aiWakeWordEnabled = status.aiWakeWordEnabled,
                localOfflineSpeechEnabled = status.localOfflineSpeechEnabled,
            )
            // A status reply reports the user's setting; it must not enable voice
            // recognition as a side effect of connecting or measuring a photo.
        }

        when (frame.commandId) {
            EyevueProtocol.CMD_RECEIVE_VOICE_DATA_START -> {
                if (photoPullProbeActive) {
                    Log.d(TAG, "Eyevue 0x97 received during photo-pull probe; wake event suppressed")
                } else {
                    Log.i(TAG, "Eyevue AI wake word started a voice stream")
                    _wakeWordEvents.tryEmit(Unit)
                }
            }

            EyevueProtocol.CMD_GET_CAPACITY,
            EyevueProtocol.CMD_RECEIVE_THUMBNAIL_COUNT,
            -> parseU16(frame.payload)?.let { count ->
                _state.value = _state.value.copy(storageCount = count)
            }

            69 -> {
                if (frame.payload.size >= 3) {
                    _state.value = _state.value.copy(
                        isVideoRecording = frame.payload[2].toInt() == 1,
                        isAudioRecording = frame.payload[1].toInt() == 1,
                    )
                }
            }
        }
    }

    private fun parseU16(payload: ByteArray): Int? {
        if (payload.size < 2) return null
        return ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
    }

    private fun updateError(message: String) {
        Log.w(TAG, message)
        _state.value = _state.value.copy(
            connectionLabel = "Eyevue error",
            lastError = message,
        )
    }
}
