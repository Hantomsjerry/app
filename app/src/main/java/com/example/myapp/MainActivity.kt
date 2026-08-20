package com.example.myapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapp.communication.DetectionParameters
import com.example.myapp.communication.EncodedParameterCommand
import com.example.myapp.communication.EndpointStore
import com.example.myapp.communication.ParameterCommandCodec
import com.example.myapp.communication.ParameterOperation
import com.example.myapp.communication.TcpEndpoint
import com.example.myapp.communication.TcpParameterClient
import com.example.myapp.communication.validateTcpEndpoint
import com.example.myapp.ui.theme.MyAppTheme
import com.example.myapp.voice.AndroidPcmRecorder
import com.example.myapp.voice.AndroidIcuPinyinEncoder
import com.example.myapp.voice.FuzzyTranscriptCorrector
import com.example.myapp.voice.LocalSpeechController
import com.example.myapp.voice.NativeQwenInferenceEngine
import com.example.myapp.voice.ParameterValue
import com.example.myapp.voice.ParameterVoiceUpdate
import com.example.myapp.voice.QwenModelStore
import com.example.myapp.voice.SenseVoiceModelStore
import com.example.myapp.voice.SenseVoiceSpeechEngine
import com.example.myapp.voice.SetParameterCommand
import com.example.myapp.voice.SpeechCorrectionDictionary
import com.example.myapp.voice.SpeechRecognizerStatus
import com.example.myapp.voice.VoiceCommandCodec
import com.example.myapp.voice.VoiceEvent
import com.example.myapp.voice.VoiceGenerationTracker
import com.example.myapp.voice.VoiceIntentParser
import com.example.myapp.voice.VoiceMicrophoneAction
import com.example.myapp.voice.VoiceTranscript
import com.example.myapp.voice.VoiceUiState
import com.example.myapp.voice.microphoneActionFor
import com.example.myapp.voice.microphoneEnabledFor
import com.example.myapp.voice.reduceVoiceState
import com.example.myapp.voice.sharedParameterSendGate
import com.example.myapp.voice.systemMicrophoneBlockMessage
import com.example.myapp.voice.voiceUpdateForResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

private val HeaderBlue = Color(0xFF0D55B7)
private val PrimaryBlue = Color(0xFF1F65D9)
private val LightBackground = Color(0xFFF2F4F8)
private val TextPrimary = Color(0xFF111827)
private val TextSecondary = Color(0xFF6B7280)
private val TrackGray = Color(0xFFE8EAEE)
private val RedAccent = Color(0xFFFF4958)
private val BlueAccent = Color(0xFF3478F6)
private val YellowAccent = Color(0xFFF6AF18)
private val DeepBlueAccent = Color(0xFF3155B7)

/** 应用唯一的 Activity，负责把 Compose 根界面挂载到窗口。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyAppTheme(dynamicColor = false) {
                DetectionParametersScreen()
            }
        }
    }
}

/**
 * 检测参数主页面，也是 TCP 与本地语音功能的编排层。
 *
 * 可持久化的表单值使用 rememberSaveable；Socket、推理引擎和进行中的 Job 只在当前
 * Composition 生命周期内保存，页面停止或离开 Composition 时统一释放。
 */
@Composable
fun DetectionParametersScreen() {
    val applicationContext = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val client = remember { TcpParameterClient() }
    val endpointStore = remember(applicationContext) { EndpointStore(applicationContext) }
    val savedEndpoint = remember(endpointStore) { endpointStore.load() }
    val coroutineScope = rememberCoroutineScope()
    val shutdownExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "voice-resource-close").apply { isDaemon = true }
        }
    }
    val shutdownSubmitted = remember { AtomicBoolean(false) }
    // 手动连接/发送与语音会话分别使用 generation，过期协程只能完成清理，不能回写新状态。
    val operationGeneration = remember { AtomicLong(0) }
    val voiceGenerationTracker = remember { VoiceGenerationTracker() }
    val modelStore = remember(applicationContext) { QwenModelStore(applicationContext) }
    val qwenEngine = remember(modelStore) { NativeQwenInferenceEngine(modelStore) }
    val voiceIntentParser = remember(qwenEngine) { VoiceIntentParser(qwenEngine) }
    val speechModelStore = remember(applicationContext) { SenseVoiceModelStore(applicationContext) }
    val speechEngine = remember(speechModelStore) { SenseVoiceSpeechEngine(speechModelStore) }
    val transcriptCorrector = remember {
        FuzzyTranscriptCorrector(SpeechCorrectionDictionary.lexemes, AndroidIcuPinyinEncoder())
    }
    val pcmRecorder = remember { AndroidPcmRecorder() }

    var host by rememberSaveable { mutableStateOf(savedEndpoint?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf(savedEndpoint?.port?.toString().orEmpty()) }
    var connectionStatusName by rememberSaveable {
        mutableStateOf(ConnectionStatus.Disconnected.name)
    }
    val connectionStatus = ConnectionStatus.valueOf(connectionStatusName)
    var connectionError by rememberSaveable { mutableStateOf<String?>(null) }
    var hostHasError by rememberSaveable { mutableStateOf(false) }
    var portHasError by rememberSaveable { mutableStateOf(false) }
    var showConnectionDialog by rememberSaveable { mutableStateOf(false) }
    var activeEndpoint by remember { mutableStateOf<TcpEndpoint?>(null) }
    var pendingCommand by remember { mutableStateOf<EncodedParameterCommand?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var connectionJob by remember { mutableStateOf<Job?>(null) }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    var voiceParseJob by remember { mutableStateOf<Job?>(null) }
    var voiceSendJob by remember { mutableStateOf<Job?>(null) }
    var voiceUiState by remember { mutableStateOf<VoiceUiState>(VoiceUiState.Idle) }
    var speechRecognizerStatus by remember {
        mutableStateOf(SpeechRecognizerStatus.Loading)
    }
    var pendingVoiceStart by remember { mutableStateOf(false) }

    var lv1Sensitivity by rememberSaveable { mutableStateOf(30) }
    var lv1Strength by rememberSaveable { mutableStateOf(100) }
    var lv1Density by rememberSaveable { mutableStateOf(0) }
    var enhancedInference by rememberSaveable { mutableStateOf(false) }
    var lv1AreaMask by rememberSaveable { mutableStateOf(false) }
    var minArea by rememberSaveable { mutableStateOf(5000) }
    var template by rememberSaveable { mutableStateOf("400mmBase.engine") }
    var lv2Strength by rememberSaveable { mutableStateOf(100) }
    var lv3Strength by rememberSaveable { mutableStateOf(60) }
    var actionDuration by rememberSaveable { mutableStateOf(1200) }
    var rejectDelay by rememberSaveable { mutableStateOf(700) }

    /** 在用户点击发送的瞬间冻结一份参数快照，避免弹窗展示后继续随 UI 改动。 */
    fun currentParameters(): DetectionParameters = DetectionParameters(
        lv1Sensitivity = lv1Sensitivity,
        lv1Strength = lv1Strength,
        lv1Density = lv1Density,
        enhancedInference = enhancedInference,
        lv1AreaMask = lv1AreaMask,
        minArea = minArea,
        template = template,
        lv2Strength = lv2Strength,
        lv3Strength = lv3Strength,
        actionDuration = actionDuration,
        rejectDelay = rejectDelay
    )

    /** 服务端确认语音命令成功后，再把新参数一次性反映到界面。 */
    fun assignParameters(parameters: DetectionParameters) {
        lv1Sensitivity = parameters.lv1Sensitivity
        lv1Strength = parameters.lv1Strength
        lv1Density = parameters.lv1Density
        enhancedInference = parameters.enhancedInference
        lv1AreaMask = parameters.lv1AreaMask
        minArea = parameters.minArea
        template = parameters.template
        lv2Strength = parameters.lv2Strength
        lv3Strength = parameters.lv3Strength
        actionDuration = parameters.actionDuration
        rejectDelay = parameters.rejectDelay
    }

    /** 所有语音状态变化都经过纯 reducer，以统一过滤非法或过期事件。 */
    fun dispatchVoiceEvent(event: VoiceEvent) {
        voiceUiState = reduceVoiceState(voiceUiState, event)
    }

    // 控制器持有自己的协程作用域；回调回到主线程后再驱动 Compose 状态。
    val localSpeechController = remember(pcmRecorder, speechEngine, transcriptCorrector) {
        LocalSpeechController(
            pcmRecorder = pcmRecorder,
            speechEngine = speechEngine,
            correctTranscript = transcriptCorrector::correct,
            debugLog = { message -> Log.d("LocalSpeechController", message) },
            onSessionCreated = { controllerGeneration ->
                val generation = voiceGenerationTracker.begin(controllerGeneration)
                dispatchVoiceEvent(VoiceEvent.NewSession(generation))
            },
            onRecordingStarted = { controllerGeneration ->
                val generation = voiceGenerationTracker.resolve(controllerGeneration)
                if (generation != null) {
                    dispatchVoiceEvent(VoiceEvent.RecordingStarted(generation))
                }
            },
            onPartialText = { result ->
                val generation = voiceGenerationTracker.resolve(result.generation)
                if (generation != null) {
                    dispatchVoiceEvent(VoiceEvent.PartialText(generation, result.text))
                }
            },
            onRecordingStopped = { controllerGeneration ->
                val generation = voiceGenerationTracker.resolve(controllerGeneration)
                if (generation != null) {
                    dispatchVoiceEvent(VoiceEvent.RecordingStopped(generation))
                }
            },
            onFinalResult = { result ->
                val generation = voiceGenerationTracker.resolve(result.generation)
                if (generation != null) {
                    if (result.correction.ambiguous) {
                        dispatchVoiceEvent(
                            VoiceEvent.FinalResult(
                                generation,
                                VoiceTranscript(
                                    raw = result.correction.rawText,
                                    corrected = result.correction.correctedText
                                ),
                                ambiguous = true
                            )
                        )
                        return@LocalSpeechController
                    }
                    val wasTranscribing =
                        (voiceUiState as? VoiceUiState.Transcribing)?.generation == generation
                    dispatchVoiceEvent(
                        VoiceEvent.FinalResult(
                            generation,
                            VoiceTranscript(
                                raw = result.correction.rawText,
                                corrected = result.correction.correctedText
                            ),
                            ambiguous = false
                        )
                    )
                    if (!wasTranscribing ||
                        (voiceUiState as? VoiceUiState.Parsing)?.generation != generation
                    ) {
                        return@LocalSpeechController
                    }
                    voiceParseJob?.cancel()
                    voiceParseJob = coroutineScope.launch {
                        dispatchVoiceEvent(VoiceEvent.ModelPreparationStarted(generation))
                        val parseResult = withContext(Dispatchers.Default) {
                            voiceIntentParser.parse(result.correction.correctedText)
                        }
                        // 推理完成时会话可能已被替换，旧结果绝不能生成新的候选指令。
                        if (!isActive || !voiceGenerationTracker.isCurrent(generation)) {
                            return@launch
                        }
                        parseResult.fold(
                            onSuccess = { command ->
                                dispatchVoiceEvent(VoiceEvent.ParsedCommand(generation, command))
                            },
                            onFailure = {
                                dispatchVoiceEvent(
                                    VoiceEvent.ParseFailed(
                                        generation,
                                        "\u672a\u80fd\u8bc6\u522b\u4e3a\u6709\u6548\u7684\u5355\u53c2\u6570\u6307\u4ee4"
                                    )
                                )
                            }
                        )
                    }
                }
            },
            onError = { failure ->
                val generation = voiceGenerationTracker.resolve(failure.generation)
                if (generation != null) {
                    dispatchVoiceEvent(
                        VoiceEvent.RecognitionFailed(
                            generation,
                            failure.message
                        )
                    )
                }
            }
        )
    }

    /** 使当前语音 generation 失效，并取消录音、解析和发送三个阶段的工作。 */
    val shutdownCleanup = remember(localSpeechController, qwenEngine, client) {
        Runnable {
            val cleanupFailures = mutableListOf<Throwable>()
            runCatching { localSpeechController.close() }
                .exceptionOrNull()
                ?.let(cleanupFailures::add)
            runCatching { qwenEngine.close() }
                .exceptionOrNull()
                ?.let(cleanupFailures::add)
            runCatching { client.close() }
                .exceptionOrNull()
                ?.let(cleanupFailures::add)
            cleanupFailures.firstOrNull()?.let { primaryFailure ->
                cleanupFailures.drop(1).forEach(primaryFailure::addSuppressed)
                throw primaryFailure
            }
        }
    }

    fun cancelVoiceWork(
        clearState: Boolean,
        cancelSpeechController: Boolean = true
    ) {
        voiceGenerationTracker.invalidate()
        pendingVoiceStart = false
        if (cancelSpeechController) {
            localSpeechController.cancel()
        }
        voiceParseJob?.cancel()
        voiceParseJob = null
        voiceSendJob?.cancel()
        voiceSendJob = null
        if (clearState) {
            voiceUiState = VoiceUiState.Idle
        }
    }

    fun dispatchVoiceFailure(message: String) {
        val generation = voiceGenerationTracker.beginUntracked()
        dispatchVoiceEvent(VoiceEvent.NewSession(generation))
        dispatchVoiceEvent(VoiceEvent.RecognitionFailed(generation, message))
    }

    fun startPreparedVoiceAttempt() {
        if (!pendingVoiceStart) return
        pendingVoiceStart = false
        try {
            localSpeechController.start()
        } catch (failure: RuntimeException) {
            dispatchVoiceFailure(
                failure.message ?: "\u672c\u5730\u8bed\u97f3\u8bc6\u522b\u65e0\u6cd5\u542f\u52a8"
            )
        }
    }

    fun startVoiceIfMicrophoneAvailable() {
        val microphoneBlockMessage = systemMicrophoneBlockMessage(
            applicationContext
                .getSystemService(AudioManager::class.java)
                ?.isMicrophoneMute == true
        )
        if (microphoneBlockMessage == null) {
            startPreparedVoiceAttempt()
        } else {
            pendingVoiceStart = false
            dispatchVoiceFailure(microphoneBlockMessage)
        }
    }

    // 权限回调返回较晚时，pendingVoiceStart 用于判断用户是否仍希望开始这次录音。
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!pendingVoiceStart) return@rememberLauncherForActivityResult
        if (granted) {
            startVoiceIfMicrophoneAvailable()
        } else {
            pendingVoiceStart = false
            dispatchVoiceFailure(
                "\u9700\u8981\u9ea6\u514b\u98ce\u6743\u9650\u624d\u80fd\u4f7f\u7528\u8bed\u97f3\u8c03\u53c2"
            )
        }
    }

    /**
     * 终止当前连接及其关联任务，并返回新的操作 generation。
     * 后续连接协程只允许在该 generation 仍为最新值时更新界面。
     */
    fun stopCommunication(closeConnectionDialog: Boolean): Long {
        val newGeneration = operationGeneration.incrementAndGet()
        connectionJob?.cancel()
        sendJob?.cancel()
        voiceSendJob?.cancel()
        connectionJob = null
        sendJob = null
        voiceSendJob = null
        val sendingState = voiceUiState as? VoiceUiState.Sending
        if (sendingState != null) {
            dispatchVoiceEvent(
                VoiceEvent.SendFailed(
                    sendingState.generation,
                    "\u8fde\u63a5\u5df2\u65ad\u5f00\uff0c\u8bf7\u91cd\u65b0\u8fde\u63a5\u540e\u518d\u8bd5"
                )
            )
        }
        client.close()
        activeEndpoint = null
        pendingCommand = null
        isSending = false
        connectionError = null
        hostHasError = false
        portHasError = false
        connectionStatusName = ConnectionStatus.Disconnected.name
        if (closeConnectionDialog) {
            showConnectionDialog = false
        }
        return newGeneration
    }

    // 应用进入后台时不保留麦克风、模型推理或网络连接。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                localSpeechController.cancel()
                cancelVoiceWork(clearState = true, cancelSpeechController = false)
                stopCommunication(closeConnectionDialog = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Composition 最终销毁时关闭所有拥有原生/系统资源的对象。
    DisposableEffect(client, localSpeechController, qwenEngine, shutdownExecutor) {
        onDispose {
            operationGeneration.incrementAndGet()
            connectionJob?.cancel()
            sendJob?.cancel()
            voiceGenerationTracker.invalidate()
            pendingVoiceStart = false
            voiceParseJob?.cancel()
            voiceSendJob?.cancel()
            if (shutdownSubmitted.compareAndSet(false, true)) {
                try {
                    shutdownExecutor.execute(shutdownCleanup)
                } catch (_: RejectedExecutionException) {
                    Thread(shutdownCleanup, "voice-resource-close-fallback").apply {
                        isDaemon = true
                    }.start()
                } finally {
                    shutdownExecutor.shutdown()
                }
            }
        }
    }

    LaunchedEffect(client) {
        val savedStatusHadLiveConnection =
            connectionStatusName == ConnectionStatus.Connected.name ||
                connectionStatusName == ConnectionStatus.Connecting.name
        if (savedStatusHadLiveConnection && !client.isConnected) {
            activeEndpoint = null
            connectionStatusName = ConnectionStatus.Disconnected.name
        }
    }

    LaunchedEffect(speechEngine) {
        speechRecognizerStatus = SpeechRecognizerStatus.Loading
        try {
            speechEngine.prepare()
            speechRecognizerStatus = SpeechRecognizerStatus.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            speechRecognizerStatus = SpeechRecognizerStatus.Failed
        }
    }

    fun currentSharedSendGate() = sharedParameterSendGate(
        voiceSending = voiceUiState is VoiceUiState.Sending,
        footerPending = pendingCommand != null,
        footerSending = isSending
    )

    val sharedSendGate = currentSharedSendGate()

    // 底部按钮发送完整参数快照；发送前先展示精确 JSON 供用户确认。
    val prepareCommand: (ParameterOperation) -> Unit = footerCommand@{ operation ->
        if (!currentSharedSendGate().canPrepareFooterCommand) {
            return@footerCommand
        }
        if (activeEndpoint == null || !client.isConnected) {
            stopCommunication(closeConnectionDialog = false)
            Toast.makeText(
                applicationContext,
                "\u8bf7\u5148\u8fde\u63a5\u670d\u52a1\u5668",
                Toast.LENGTH_SHORT
            ).show()
            showConnectionDialog = true
        } else {
            pendingCommand = ParameterCommandCodec.encode(
                operation = operation,
                timestamp = System.currentTimeMillis(),
                parameters = currentParameters()
            )
        }
    }

    // 麦克风按钮的行为完全由语音状态机决定，处理中不会接受重复点击。
    val onMicrophoneClick: () -> Unit = microphone@{
        val action = microphoneActionFor(voiceUiState)
        if (action == VoiceMicrophoneAction.StartRecording &&
            speechRecognizerStatus != SpeechRecognizerStatus.Ready
        ) {
            return@microphone
        }
        when (action) {
            VoiceMicrophoneAction.StopAndTranscribe ->
                localSpeechController.stopAndTranscribe()

            VoiceMicrophoneAction.Ignore -> return@microphone
            VoiceMicrophoneAction.StartRecording -> {
                cancelVoiceWork(clearState = false)
                pendingVoiceStart = true
                if (ContextCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                ) {
                    startVoiceIfMicrophoneAvailable()
                } else {
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    // 语音候选保留到服务端确认成功；失败时返回 Ready，允许用户原样重试。
    val onVoiceApply: () -> Unit = voiceApply@{
        val ready = voiceUiState as? VoiceUiState.Ready ?: return@voiceApply
        if (!currentSharedSendGate().canApplyVoiceCommand) {
            return@voiceApply
        }
        if (activeEndpoint == null || !client.isConnected) {
            if (!client.isConnected) {
                client.close()
                activeEndpoint = null
                connectionStatusName = ConnectionStatus.Disconnected.name
            }
            voiceUiState = ready.copy(
                retryError = "\u8bf7\u5148\u8fde\u63a5\u670d\u52a1\u5668\uff0c\u5019\u9009\u6307\u4ee4\u5df2\u4fdd\u7559"
            )
            showConnectionDialog = true
            return@voiceApply
        }

        val generation = ready.generation
        val command = ready.command
        val wireText = VoiceCommandCodec.compact(command) + "\n"
        dispatchVoiceEvent(VoiceEvent.SendStarted(generation))
        voiceSendJob?.cancel()
        voiceSendJob = coroutineScope.launch {
            try {
                val response = client.sendAndReceive(wireText)
                val sending = voiceUiState as? VoiceUiState.Sending
                if (!isActive || !voiceGenerationTracker.isCurrent(generation) ||
                    sending?.generation != generation || sending.command != command
                ) {
                    return@launch
                }
                when (val update = currentParameters().voiceUpdateForResponse(command, response)) {
                    is ParameterVoiceUpdate.Applied -> {
                        assignParameters(update.parameters)
                        dispatchVoiceEvent(VoiceEvent.SendSucceeded(generation))
                    }
                    ParameterVoiceUpdate.Rejected -> dispatchVoiceEvent(
                        VoiceEvent.SendFailed(
                            generation,
                            "\u670d\u52a1\u5668\u672a\u8fd4\u56de success\uff0c\u8bf7\u91cd\u8bd5"
                        )
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val sending = voiceUiState as? VoiceUiState.Sending
                if (isActive && voiceGenerationTracker.isCurrent(generation) &&
                    sending?.generation == generation && sending.command == command
                ) {
                    client.close()
                    activeEndpoint = null
                    connectionStatusName = ConnectionStatus.Disconnected.name
                    dispatchVoiceEvent(
                        VoiceEvent.SendFailed(generation, sendFailureMessage(failure))
                    )
                }
            } finally {
                if (voiceSendJob === coroutineContext[Job]) {
                    voiceSendJob = null
                }
            }
        }
    }

    // 成功提示短暂保留，1.5 秒后回到待命状态。
    LaunchedEffect(voiceUiState) {
        val success = voiceUiState as? VoiceUiState.Success ?: return@LaunchedEffect
        delay(1_500)
        if (voiceGenerationTracker.isCurrent(success.generation) && voiceUiState == success) {
            voiceUiState = VoiceUiState.Idle
        }
    }

    Surface(color = LightBackground, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopStatusHeader(
                connectionStatus = connectionStatus,
                onConnectionClick = { showConnectionDialog = true }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VoiceTuningCard(
                    state = voiceUiState,
                    recognizerStatus = speechRecognizerStatus,
                    voiceApplyEnabled = sharedSendGate.canApplyVoiceCommand,
                    onMicrophoneClick = onMicrophoneClick,
                    onApply = onVoiceApply
                )
                DetectionSectionCard(
                    lv1Sensitivity = lv1Sensitivity,
                    onLv1SensitivityChange = { lv1Sensitivity = it },
                    lv1Strength = lv1Strength,
                    onLv1StrengthChange = { lv1Strength = it },
                    lv1Density = lv1Density,
                    onLv1DensityChange = { lv1Density = it },
                    enhancedInference = enhancedInference,
                    onEnhancedInferenceChange = { enhancedInference = it },
                    lv1AreaMask = lv1AreaMask,
                    onLv1AreaMaskChange = { lv1AreaMask = it },
                    minArea = minArea,
                    onMinAreaChange = { minArea = it },
                    template = template,
                    lv2Strength = lv2Strength,
                    onLv2StrengthChange = { lv2Strength = it },
                    lv3Strength = lv3Strength,
                    onLv3StrengthChange = { lv3Strength = it }
                )
                RejectSectionCard(
                    actionDuration = actionDuration,
                    onActionDurationChange = { actionDuration = it },
                    rejectDelay = rejectDelay,
                    onRejectDelayChange = { rejectDelay = it }
                )
            }
            BottomActionBar(
                enabled = sharedSendGate.canPrepareFooterCommand,
                onSave = { prepareCommand(ParameterOperation.Save) },
                onApply = { prepareCommand(ParameterOperation.Apply) },
                onSync = { prepareCommand(ParameterOperation.Sync) }
            )
        }
    }

    // 连接弹窗中的输入可以持久化，但活动 Socket 不跨页面生命周期恢复。
    if (showConnectionDialog) {
        ConnectionSettingsDialog(
            host = host,
            port = port,
            connectionStatus = connectionStatus,
            errorMessage = connectionError,
            hostHasError = hostHasError,
            portHasError = portHasError,
            onHostChange = {
                host = it
                connectionError = null
                hostHasError = false
                portHasError = false
            },
            onPortChange = {
                port = it
                connectionError = null
                hostHasError = false
                portHasError = false
            },
            onConnect = {
                validateTcpEndpoint(host, port).fold(
                    onSuccess = { endpoint ->
                        val operationId = stopCommunication(closeConnectionDialog = false)
                        connectionStatusName = ConnectionStatus.Connecting.name
                        connectionJob = coroutineScope.launch {
                            try {
                                client.connect(endpoint)
                                if (!isActive || operationGeneration.get() != operationId) {
                                    return@launch
                                }
                                host = endpoint.host
                                port = endpoint.port.toString()
                                endpointStore.save(endpoint)
                                activeEndpoint = endpoint
                                connectionStatusName = ConnectionStatus.Connected.name
                                connectionError = null
                                showConnectionDialog = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                if (isActive && operationGeneration.get() == operationId) {
                                    client.close()
                                    activeEndpoint = null
                                    pendingCommand = null
                                    connectionStatusName = ConnectionStatus.Failed.name
                                    connectionError = connectionFailureMessage(failure)
                                }
                            } finally {
                                if (operationGeneration.get() == operationId) {
                                    connectionJob = null
                                }
                            }
                        }
                    },
                    onFailure = { failure ->
                        val invalidHost = host.trim().isEmpty()
                        hostHasError = invalidHost
                        portHasError = !invalidHost
                        val hasLiveActiveConnection =
                            activeEndpoint != null && client.isConnected
                        connectionStatusName = if (hasLiveActiveConnection) {
                            ConnectionStatus.Connected.name
                        } else {
                            ConnectionStatus.Failed.name
                        }
                        connectionError = failure.message
                            ?: "\u8bf7\u68c0\u67e5\u670d\u52a1\u5668\u5730\u5740\u548c\u7aef\u53e3"
                    }
                )
            },
            onDisconnect = {
                stopCommunication(closeConnectionDialog = false)
            },
            onDismiss = {
                if (connectionStatusName == ConnectionStatus.Connecting.name) {
                    stopCommunication(closeConnectionDialog = true)
                } else {
                    showConnectionDialog = false
                }
            }
        )
    }

    // pendingCommand 同时是发送确认弹窗的显示状态和不可变发送快照。
    pendingCommand?.let { command ->
        SendConfirmationDialog(
            endpointLabel = activeEndpoint?.label.orEmpty(),
            command = command,
            isSending = isSending,
            onConfirm = {
                if (!isSending && pendingCommand === command) {
                    val operationId = operationGeneration.incrementAndGet()
                    sendJob?.cancel()
                    isSending = true
                    connectionError = null
                    sendJob = coroutineScope.launch {
                        try {
                            val response = client.sendAndReceive(command.wireText)
                            if (!isActive || operationGeneration.get() != operationId) {
                                return@launch
                            }
                            val feedback = if (ParameterCommandCodec.isSuccessResponse(response)) {
                                "${command.operation.displayName}\u6210\u529f"
                            } else {
                                "\u670d\u52a1\u5668\u8fd4\u56de\u5931\u8d25"
                            }
                            pendingCommand = null
                            Toast.makeText(
                                applicationContext,
                                feedback,
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            if (isActive && operationGeneration.get() == operationId) {
                                client.close()
                                activeEndpoint = null
                                connectionStatusName = ConnectionStatus.Disconnected.name
                                val feedback = sendFailureMessage(failure)
                                connectionError = feedback
                                pendingCommand = null
                                Toast.makeText(
                                    applicationContext,
                                    feedback,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } finally {
                            if (operationGeneration.get() == operationId) {
                                isSending = false
                                sendJob = null
                            }
                        }
                    }
                }
            },
            onCancel = {
                if (!isSending) {
                    pendingCommand = null
                }
            }
        )
    }
}

/** 顶部栏显示页面名称、TCP 连接状态以及上位机同步提示。 */
@Composable
private fun TopStatusHeader(
    connectionStatus: ConnectionStatus,
    onConnectionClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(HeaderBlue)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "检测参数",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(
                text = connectionStatus.displayName,
                dotColor = connectionStatusDotColor(connectionStatus),
                onClick = onConnectionClick
            )
            StatusPill(text = "上位机同步", leadingIcon = "▣")
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    dotColor: Color? = null,
    leadingIcon: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(50))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.08f))
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        if (leadingIcon != null) {
            Text(text = leadingIcon, color = Color.White, fontSize = 12.sp)
        }
        Text(text = text, color = Color.White, fontSize = 12.sp)
    }
}

/** 语音调参卡片：展示录音/推理状态、识别文本和待确认的单参数命令。 */
@Composable
private fun VoiceTuningCard(
    state: VoiceUiState,
    recognizerStatus: SpeechRecognizerStatus,
    voiceApplyEnabled: Boolean,
    onMicrophoneClick: () -> Unit,
    onApply: () -> Unit
) {
    val transcript = when (state) {
        is VoiceUiState.Recording -> state.partialTranscript
        is VoiceUiState.Parsing -> state.transcript.corrected
        is VoiceUiState.PreparingModel -> state.transcript.corrected
        is VoiceUiState.Ready -> state.transcript.corrected
        is VoiceUiState.Sending -> state.transcript.corrected
        is VoiceUiState.Success -> state.transcript.corrected
        VoiceUiState.Idle,
        is VoiceUiState.PreparingSpeechModel,
        is VoiceUiState.Transcribing,
        is VoiceUiState.Error -> ""
    }
    val command = when (state) {
        is VoiceUiState.Ready -> state.command
        is VoiceUiState.Sending -> state.command
        is VoiceUiState.Success -> state.command
        else -> null
    }
    val statusText = when (state) {
        VoiceUiState.Idle -> when (recognizerStatus) {
            SpeechRecognizerStatus.Loading -> "\u6b63\u5728\u52a0\u8f7d\u8bed\u97f3\u8bc6\u522b\u6a21\u578b..."
            SpeechRecognizerStatus.Ready -> "\u7b49\u5f85\u8bed\u97f3\u6307\u4ee4"
            SpeechRecognizerStatus.Failed -> "\u8bed\u97f3\u8bc6\u522b\u6a21\u578b\u52a0\u8f7d\u5931\u8d25"
        }
        is VoiceUiState.PreparingSpeechModel -> "\u6b63\u5728\u52a0\u8f7d\u8bed\u97f3\u6a21\u578b"
        is VoiceUiState.Recording ->
            "\u6b63\u5728\u8046\u542c\uff0c\u518d\u6b21\u70b9\u51fb\u7ed3\u675f"
        is VoiceUiState.Transcribing -> "\u6b63\u5728\u6574\u7406\u8bc6\u522b\u7ed3\u679c"
        is VoiceUiState.Parsing -> "\u6b63\u5728\u89e3\u6790\u8bed\u97f3..."
        is VoiceUiState.PreparingModel -> "\u6b63\u5728\u51c6\u5907\u672c\u5730\u6a21\u578b..."
        is VoiceUiState.Ready -> state.retryError ?: "\u6307\u4ee4\u5df2\u5c31\u7eea"
        is VoiceUiState.Sending -> "\u6b63\u5728\u53d1\u9001..."
        is VoiceUiState.Success -> "\u5df2\u5e94\u7528 ${voiceCommandSummary(state.command)}"
        is VoiceUiState.Error -> state.message
    }
    val microphoneEnabled = microphoneEnabledFor(state, recognizerStatus)
    val isRecording = state is VoiceUiState.Recording
    val applyEnabled = state is VoiceUiState.Ready && voiceApplyEnabled

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoiceBarsIcon()
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "\u8bed\u97f3\u8c03\u53c2",
                        color = PrimaryBlue,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "\u8bf4\u51fa\u53c2\u6570\u6307\u4ee4\uff0c\u8bc6\u522b\u540e\u5728\u5361\u7247\u5185\u5e94\u7528",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = if (transcript.isBlank()) {
                        "\u201c\u5c06 Lv1 \u5f3a\u5ea6\u8c03\u5230 80\u201d"
                    } else {
                        "\u5df2\u8bc6\u522b\uff1a$transcript"
                    },
                    color = PrimaryBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFFAFBFD))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    if (command != null && state !is VoiceUiState.Success) {
                        Text(
                            text = VoiceCommandCodec.pretty(command),
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(voiceStatusColor(state))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            color = if (state is VoiceUiState.Error ||
                                (state is VoiceUiState.Ready && state.retryError != null)
                            ) RedAccent else TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (command != null && state !is VoiceUiState.Success) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "\u5e94\u7528",
                                color = if (applyEnabled) PrimaryBlue else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .clickable(enabled = applyEnabled, onClick = onApply)
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.width(106.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isRecording) "\u70b9\u51fb\u7ed3\u675f" else "\u8bed\u97f3\u8f93\u5165",
                    color = if (microphoneEnabled) PrimaryBlue else TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                MicrophoneButton(
                    enabled = microphoneEnabled,
                    recording = isRecording,
                    onClick = onMicrophoneClick
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = when {
                        isRecording -> "\u6b63\u5728\u5f55\u97f3"
                        microphoneEnabled -> "\u70b9\u51fb\u8bf4\u8bdd"
                        else -> "\u5904\u7406\u4e2d"
                    },
                    color = if (microphoneEnabled) PrimaryBlue else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 检测参数区域，组合数值滑块、步进器、开关和固定模板。 */
@Composable
private fun DetectionSectionCard(
    lv1Sensitivity: Int,
    onLv1SensitivityChange: (Int) -> Unit,
    lv1Strength: Int,
    onLv1StrengthChange: (Int) -> Unit,
    lv1Density: Int,
    onLv1DensityChange: (Int) -> Unit,
    enhancedInference: Boolean,
    onEnhancedInferenceChange: (Boolean) -> Unit,
    lv1AreaMask: Boolean,
    onLv1AreaMaskChange: (Boolean) -> Unit,
    minArea: Int,
    onMinAreaChange: (Int) -> Unit,
    template: String,
    lv2Strength: Int,
    onLv2StrengthChange: (Int) -> Unit,
    lv3Strength: Int,
    onLv3StrengthChange: (Int) -> Unit
) {
    SectionCard {
        CardHeader(icon = "☷", title = "检测参数", action = "↻  恢复默认")
        Spacer(modifier = Modifier.height(8.dp))
        ParameterControlRow("Lv1  灵敏度", RedAccent, lv1Sensitivity, 0..100, 5, onLv1SensitivityChange)
        ParameterControlRow("Lv1  强度", RedAccent, lv1Strength, 0..120, 5, onLv1StrengthChange)
        ParameterControlRow("Lv1  浓淡", RedAccent, lv1Density, 0..100, 5, onLv1DensityChange)
        ToggleControlRow("强化推理", enhancedInference, onEnhancedInferenceChange)
        ToggleControlRow("是否打开Lv1面积屏蔽", lv1AreaMask, onLv1AreaMaskChange)
        ParameterControlRow("最小面积", RedAccent, minArea, 0..10000, 100, onMinAreaChange)
        TemplateControlRow(template)
        ParameterControlRow("Lv2  强度", BlueAccent, lv2Strength, 0..120, 5, onLv2StrengthChange)
        ParameterControlRow("Lv3  强度", YellowAccent, lv3Strength, 0..100, 5, onLv3StrengthChange)
    }
}

/** 剔除动作相关的持续时间和延迟参数。 */
@Composable
private fun RejectSectionCard(
    actionDuration: Int,
    onActionDurationChange: (Int) -> Unit,
    rejectDelay: Int,
    onRejectDelayChange: (Int) -> Unit
) {
    SectionCard {
        CardHeader(icon = "▥", title = "剔除参数")
        Spacer(modifier = Modifier.height(8.dp))
        ParameterControlRow("动作持续", DeepBlueAccent, actionDuration, 0..2000, 100, onActionDurationChange)
        ParameterControlRow("剔除延时", DeepBlueAccent, rejectDelay, 0..1500, 100, onRejectDelayChange)
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(0.dp)),
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            content = content
        )
    }
}

@Composable
private fun CardHeader(icon: String, title: String, action: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, color = PrimaryBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = title, color = PrimaryBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.weight(1f))
        if (action != null) {
            Text(text = action, color = PrimaryBlue, fontSize = 12.sp)
        }
    }
}

/** 通用整数参数行；滑块可连续取整，加减按钮按业务步长调整。 */
@Composable
private fun ParameterControlRow(
    label: String,
    accent: Color,
    value: Int,
    range: IntRange,
    step: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.width(122.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = TextPrimary, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent,
                inactiveTrackColor = TrackGray,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Stepper(
            value = value,
            range = range,
            onValueChange = onValueChange,
            onMinus = { onValueChange(adjustParameterValue(value, -1, step, range)) },
            onPlus = { onValueChange(adjustParameterValue(value, 1, step, range)) }
        )
    }
}

@Composable
private fun Stepper(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var editorValue by remember { mutableStateOf(TextFieldValue(value.toString())) }
    var isEditing by remember { mutableStateOf(false) }

    fun commitInput() {
        val committedValue = commitParameterInput(editorValue.text, value, range)
        editorValue = TextFieldValue(
            text = committedValue.toString(),
            selection = TextRange(committedValue.toString().length)
        )
        if (committedValue != value) {
            onValueChange(committedValue)
        }
    }

    LaunchedEffect(value) {
        val text = value.toString()
        editorValue = TextFieldValue(text = text, selection = TextRange(text.length))
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            editorValue = editorValue.copy(
                selection = TextRange(0, editorValue.text.length)
            )
        }
    }

    Row(
        modifier = Modifier
            .width(126.dp)
            .height(32.dp)
            .border(1.dp, Color(0xFFD8DEE8), RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperButton("−", onMinus)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .border(1.dp, Color(0xFFE5EAF2)),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = editorValue,
                onValueChange = { editorValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        val wasEditing = isEditing
                        isEditing = focusState.isFocused
                        if (wasEditing && !focusState.isFocused) {
                            commitInput()
                        }
                    },
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
                cursorBrush = SolidColor(PrimaryBlue)
            )
        }
        StepperButton("+", onPlus)
    }
}

@Composable
private fun RowScope.StepperButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(32.dp)
            .height(32.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = PrimaryBlue, fontSize = 20.sp)
    }
}

@Composable
private fun ToggleControlRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextPrimary, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "ⓘ", color = Color(0xFF9CA3AF), fontSize = 11.sp)
        Spacer(modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PrimaryBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE1E4EA),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
    HorizontalDivider(color = Color(0xFFEDEFF3), thickness = 0.5.dp)
}

@Composable
private fun TemplateControlRow(template: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.width(122.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "模版", color = TextPrimary, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(RedAccent)
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .border(1.dp, Color(0xFFD8DEE8), RoundedCornerShape(2.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = template, color = TextPrimary, fontSize = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "▼", color = TextPrimary, fontSize = 10.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .border(1.dp, Color(0xFFD8DEE8), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "i", color = PrimaryBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 底部三种手动操作共享发送槽，防止与语音命令并发写同一连接。 */
@Composable
private fun BottomActionBar(
    enabled: Boolean,
    onSave: () -> Unit,
    onApply: () -> Unit,
    onSync: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FooterOutlinedButton("▣  保存参数", Modifier.weight(1f), enabled, onSave)
            FooterOutlinedButton("▣  应用参数", Modifier.weight(1f), enabled, onApply)
            Button(
                onClick = onSync,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                shape = RoundedCornerShape(5.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1260DF))
            ) {
                Text(text = "↥  同步到设备", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "⟳  参数将同步到上位机软件并应用到设备",
            color = Color(0xFF6B7A99),
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RowScope.FooterOutlinedButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(5.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryBlue)
    ) {
        Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** 根据当前状态切换可点击性和录音中的红色反馈。 */
@Composable
private fun MicrophoneButton(
    enabled: Boolean,
    recording: Boolean,
    onClick: () -> Unit
) {
    val outerColor = if (enabled) Color(0xFFEAF1FF) else Color(0xFFF0F1F3)
    val middleColor = if (enabled) Color(0xFFD8E6FF) else Color(0xFFE3E5E8)
    val centerColor = when {
        !enabled -> Color(0xFF9CA3AF)
        recording -> RedAccent
        else -> BlueAccent
    }
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(outerColor)
        )
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(middleColor)
        )
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(centerColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\uD83C\uDFA4",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun VoiceBarsIcon() {
    Canvas(modifier = Modifier.size(24.dp)) {
        val bars = listOf(10f, 18f, 12f, 22f, 14f)
        val spacing = size.width / 6f
        bars.forEachIndexed { index, barHeight ->
            val x = spacing * (index + 1)
            drawLine(
                color = PrimaryBlue,
                start = Offset(x, size.height / 2f - barHeight / 2f),
                end = Offset(x, size.height / 2f + barHeight / 2f),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun voiceCommandSummary(command: SetParameterCommand): String =
    "${command.device.wireName} / ${command.parameter.wireName} = ${voiceValueText(command.value)}"

private fun voiceValueText(value: ParameterValue): String = when (value) {
    is ParameterValue.IntValue -> value.value.toString()
    is ParameterValue.BooleanValue -> value.value.toString()
    is ParameterValue.StringValue -> value.value
}

private fun voiceStatusColor(state: VoiceUiState): Color = when (state) {
    VoiceUiState.Idle -> Color(0xFF9CA3AF)
    is VoiceUiState.Recording -> RedAccent
    is VoiceUiState.Transcribing,
    is VoiceUiState.PreparingSpeechModel,
    is VoiceUiState.Parsing,
    is VoiceUiState.PreparingModel,
    is VoiceUiState.Sending ->
        YellowAccent
    is VoiceUiState.Ready, is VoiceUiState.Success -> Color(0xFF45C84A)
    is VoiceUiState.Error -> RedAccent
}

private fun connectionStatusDotColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.Connected -> Color(0xFF8AEA5C)
    ConnectionStatus.Connecting -> Color(0xFFFFC247)
    ConnectionStatus.Failed -> Color(0xFFFF6B6B)
    ConnectionStatus.Disconnected -> Color(0xFF9CA3AF)
}

private fun connectionFailureMessage(failure: Throwable): String = when (failure) {
    is SocketTimeoutException ->
        "\u8fde\u63a5\u8d85\u65f6\uff0c\u8bf7\u68c0\u67e5\u670d\u52a1\u5668"
    is UnknownHostException ->
        "\u65e0\u6cd5\u89e3\u6790\u670d\u52a1\u5668\u5730\u5740"
    is ConnectException ->
        "\u65e0\u6cd5\u8fde\u63a5\u670d\u52a1\u5668\uff0c\u8bf7\u68c0\u67e5\u5730\u5740\u548c\u7aef\u53e3"
    else ->
        "\u8fde\u63a5\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u548c\u670d\u52a1\u5668"
}

private fun sendFailureMessage(failure: Throwable): String = when (failure) {
    is SocketTimeoutException ->
        "\u670d\u52a1\u5668\u54cd\u5e94\u8d85\u65f6\uff0c\u8bf7\u91cd\u65b0\u8fde\u63a5"
    is EOFException ->
        "\u670d\u52a1\u5668\u5df2\u65ad\u5f00\u8fde\u63a5\uff0c\u8bf7\u91cd\u65b0\u8fde\u63a5"
    else ->
        "\u53d1\u9001\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u8fde\u63a5"
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun DetectionParametersPreview() {
    MyAppTheme(dynamicColor = false) {
        DetectionParametersScreen()
    }
}
