package com.google.ai.edge.gallery.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.customtasks.voicepicker.VOICE_PICKER_TASK_ID
import com.google.ai.edge.gallery.customtasks.voicepicker.VoicePickerTask
import com.google.ai.edge.gallery.customtasks.agentchat.VoicePickingToolTrace
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.chat.AudioRecorderPanel
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Message

private const val VOICE_PICKER_SAMPLE_RATE = 16000
private const val VOICE_PICKER_SILENCE_MS = 1000L
private const val VOICE_PICKER_SPEECH_THRESHOLD = 3500
private val TRANSCRIPT_MAX_HEIGHT = 240.dp
private val TOOL_TRACE_MAX_HEIGHT = 112.dp

private data class VoicePickerTranscriptLine(
  val id: Int,
  val speaker: String,
  val content: String,
)

private enum class VoicePickerState(val label: String) {
  PREPARING("Preparing on-device model"),
  WAITING_FOR_START("Ready — say \"Start Order 42\" to start order"),
  SENDING_TO_GEMMA("Sending audio to Gemma"),
  GEMMA_RESPONDING("Gemma is responding"),
  SPEAKING_NAVIGATION("Gemma is speaking the next location"),
  WAITING_FOR_ARRIVAL("Waiting for arrival signal"),
  LOCAL_LOCATION_PROMPT("Sound detected — prompting for check digits"),
  LISTENING_FOR_CHECK_DIGITS("Listening for location check digits"),
  SPEAKING_WRONG_CHECK_DIGITS("Gemma is explaining the check-digit mismatch"),
  SPEAKING_ITEM_TASK("Gemma is speaking the item to pick"),
  WAITING_FOR_ITEM_LOCATION("Waiting for item-located signal"),
  LOCAL_PICK_PROMPT("Sound detected — prompting for item and quantity"),
  LISTENING_FOR_PICK_CONFIRMATION("Listening for item and quantity"),
  SPEAKING_WRONG_PICK_CONFIRMATION("Gemma is explaining the item or quantity mismatch"),
  COMPLETE("Order complete"),
  ERROR("Unable to start Voice Picker"),
}

private fun routingContextFor(state: VoicePickerState): String =
  when (state) {
    VoicePickerState.WAITING_FOR_START ->
      """
      Routing context (do not say this aloud): this is a fresh worker clip containing a start-order
      command. Extract the order number only from this new clip. Do not reuse a prior order number.
      """.trimIndent()
    VoicePickerState.LISTENING_FOR_CHECK_DIGITS ->
      """
      Routing context (do not say this aloud): this is a fresh worker clip answering the location
      check-digit prompt. Call verify_check_digits using only the digits audibly present in this
      new clip. Never reuse digits from an earlier instruction, correction, or conversation turn.
      Only call repeat_instruction if the new clip explicitly asks to repeat.
      """.trimIndent()
    VoicePickerState.LISTENING_FOR_PICK_CONFIRMATION ->
      """
      Routing context (do not say this aloud): this is a fresh worker clip confirming the item
      digits and quantity. Extract both values only from this new clip. Never infer either value
      from an earlier instruction or conversation turn. Only call repeat_instruction if the new
      clip explicitly asks to repeat.
      """.trimIndent()
    else -> ""
  }

/**
 * A compact replayable checkpoint used only after Kotlin rejects a response. It preserves the
 * worker's place in the flow without exposing target digits or retaining the rejected turn.
 */
private fun cleanCheckpointFor(state: VoicePickerState): List<Message> =
  when (state) {
    VoicePickerState.LISTENING_FOR_CHECK_DIGITS ->
      listOf(
        Message.user("The worker has arrived at the current warehouse location."),
        Message.model("Read the three check digits on the location label."),
      )
    VoicePickerState.LISTENING_FOR_PICK_CONFIRMATION ->
      listOf(
        Message.user("The worker has located the requested product."),
        Message.model("Confirm the item's three ending digits and the quantity picked."),
      )
    else -> listOf()
  }

@Composable
fun VoicePickerScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onNavigateUp: () -> Unit,
  viewModel: LlmChatViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val modelUiState by modelManagerViewModel.uiState.collectAsState()
  val voiceTask = modelManagerViewModel.getTaskById(VOICE_PICKER_TASK_ID)
  val voicePickerTask =
    modelManagerViewModel.getCustomTaskByTaskId(VOICE_PICKER_TASK_ID) as? VoicePickerTask
  val model =
    remember(modelUiState.tasks, modelUiState.modelDownloadStatus) {
      modelManagerViewModel.getAllDownloadedModels().firstOrNull { it.llmSupportAudio }
    }
  val modelStatus = model?.let { modelUiState.modelInitializationStatus[it.name]?.status }
  val recorderTrigger by viewModel.openAudioRecorderTrigger.collectAsState()
  var state by remember { mutableStateOf(VoicePickerState.PREPARING) }
  var amplitude by remember { mutableIntStateOf(0) }
  var showRecorder by remember { mutableStateOf(false) }
  var recorderGeneration by remember { mutableIntStateOf(0) }
  var transcript by remember { mutableStateOf(emptyList<VoicePickerTranscriptLine>()) }
  var nextTranscriptId by remember { mutableIntStateOf(0) }
  var lastToolTrace by remember { mutableStateOf<VoicePickingToolTrace?>(null) }
  var discardFailedGemmaTurn by remember { mutableStateOf(false) }

  fun addTranscriptLine(speaker: String, content: String): Int {
    val id = nextTranscriptId++
    transcript = (transcript + VoicePickerTranscriptLine(id, speaker, content)).takeLast(20)
    return id
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) viewModel.rearmHandsFreeLoop() else state = VoicePickerState.ERROR
    }

  fun beginListening() {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      showRecorder = true
      recorderGeneration++
    } else {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  LaunchedEffect(model, voiceTask) {
    when {
      model == null || voiceTask == null -> state = VoicePickerState.ERROR
      else -> modelManagerViewModel.initializeModel(context, voiceTask, model)
    }
  }

  LaunchedEffect(modelStatus) {
    if (modelStatus == ModelInitializationStatusType.INITIALIZED) {
      viewModel.setHandsFreeMode(true)
      state = VoicePickerState.WAITING_FOR_START
    } else if (modelStatus == ModelInitializationStatusType.ERROR) {
      state = VoicePickerState.ERROR
    }
  }

  LaunchedEffect(recorderTrigger) {
    if (
      recorderTrigger > 0L &&
        modelStatus == ModelInitializationStatusType.INITIALIZED &&
        state != VoicePickerState.COMPLETE &&
        state != VoicePickerState.ERROR
    ) {
      state =
        when (state) {
          VoicePickerState.SPEAKING_NAVIGATION -> VoicePickerState.WAITING_FOR_ARRIVAL
          VoicePickerState.LOCAL_LOCATION_PROMPT -> VoicePickerState.LISTENING_FOR_CHECK_DIGITS
          VoicePickerState.SPEAKING_WRONG_CHECK_DIGITS ->
            VoicePickerState.LISTENING_FOR_CHECK_DIGITS
          VoicePickerState.SPEAKING_ITEM_TASK -> VoicePickerState.WAITING_FOR_ITEM_LOCATION
          VoicePickerState.LOCAL_PICK_PROMPT -> VoicePickerState.LISTENING_FOR_PICK_CONFIRMATION
          VoicePickerState.SPEAKING_WRONG_PICK_CONFIRMATION ->
            VoicePickerState.LISTENING_FOR_PICK_CONFIRMATION
          else -> state
        }
      beginListening()
    }
  }

  DisposableEffect(model, voiceTask) {
    onDispose {
      viewModel.setHandsFreeMode(false)
      if (model != null && voiceTask != null) {
        modelManagerViewModel.cleanupModel(context, voiceTask, model)
      }
    }
  }

  fun sendAudio(
    audioData: ByteArray,
    nextState: VoicePickerState,
    recoveryState: VoicePickerState? = null,
    shouldRecover: () -> Boolean = { false },
  ) {
    val selectedModel = model ?: return
    val selectedTask = voicePickerTask ?: run {
      state = VoicePickerState.ERROR
      return
    }
    val routingContext = routingContextFor(state)
    state = VoicePickerState.SENDING_TO_GEMMA
    addTranscriptLine("Worker audio", "Sent to Gemma")
    val agentLineId = addTranscriptLine("Agent", "")
    fun runInference() {
      viewModel.generateResponse(
        model = selectedModel,
        input = routingContext,
        audioMessages = listOf(ChatMessageAudioClip(audioData, VOICE_PICKER_SAMPLE_RATE, ChatSide.USER)),
        onFirstToken = { state = VoicePickerState.GEMMA_RESPONDING },
        onResponseDelta = { delta ->
          transcript =
            transcript.map { line ->
              if (line.id == agentLineId) line.copy(content = line.content + delta) else line
            }
        },
        onDone = {
          lastToolTrace = selectedTask.voicePickingTools.getLastToolTrace()
          val shouldEnterRecovery = recoveryState != null && shouldRecover()
          if (shouldEnterRecovery) {
            // The next attempt must not see the rejected audio or its correction. Resetting here
            // would lose the current reply while it is still being spoken, so reset just before
            // the retry instead.
            discardFailedGemmaTurn = true
          }
          state =
            when {
              selectedTask.voicePickingTools.isComplete() -> VoicePickerState.COMPLETE
              shouldEnterRecovery -> recoveryState
              else -> nextState
            }
        },
        onError = { state = VoicePickerState.ERROR },
      )
    }
    if (discardFailedGemmaTurn) {
      discardFailedGemmaTurn = false
      viewModel.resetConversationForFreshTurn(
        model = selectedModel,
        systemInstruction = selectedTask.conversationSystemInstruction(),
        tools = selectedTask.conversationTools(),
        initialMessages = cleanCheckpointFor(state),
        onDone = ::runInference,
      )
    } else {
      runInference()
    }
  }

  fun speakLocalPrompt(prompt: String, speakingState: VoicePickerState) {
    state = speakingState
    addTranscriptLine("Agent", prompt)
    viewModel.speakLocalPrompt(prompt)
  }

  Scaffold(
    topBar = {
      GalleryTopAppBar(
        title = "Voice Picker",
        leftAction = AppBarAction(AppBarActionType.NAVIGATE_UP, onNavigateUp),
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp).padding(innerPadding),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Voice Picker", style = MaterialTheme.typography.headlineMedium)
      if (state == VoicePickerState.WAITING_FOR_START) {
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
          Text(
            text = "Say \"Start Order 42\" to start order",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
      }
      DebugStateCard(state = state, amplitude = amplitude, model = model, toolTrace = lastToolTrace)
      ConversationTranscriptCard(transcript)
      if (showRecorder && voiceTask != null) {
        key(recorderGeneration) {
          AudioRecorderPanel(
            task = voiceTask,
            onAmplitudeChanged = { amplitude = it },
            onSendAudioClip = { audioData ->
              showRecorder = false
              when (state) {
                VoicePickerState.WAITING_FOR_START ->
                  sendAudio(audioData, VoicePickerState.SPEAKING_NAVIGATION)
                VoicePickerState.WAITING_FOR_ARRIVAL -> {
                  val prompt = voicePickerTask?.voicePickingTools?.confirmArrival()?.get("sayText") as? String
                  if (prompt != null) speakLocalPrompt(prompt, VoicePickerState.LOCAL_LOCATION_PROMPT)
                }
                VoicePickerState.LISTENING_FOR_CHECK_DIGITS ->
                  sendAudio(
                    audioData = audioData,
                    nextState = VoicePickerState.SPEAKING_ITEM_TASK,
                    recoveryState = VoicePickerState.SPEAKING_WRONG_CHECK_DIGITS,
                    shouldRecover = { voicePickerTask?.voicePickingTools?.isAwaitingCheckDigits() == true },
                  )
                VoicePickerState.WAITING_FOR_ITEM_LOCATION -> {
                  val prompt = voicePickerTask?.voicePickingTools?.confirmItemLocated()?.get("sayText") as? String
                  if (prompt != null) speakLocalPrompt(prompt, VoicePickerState.LOCAL_PICK_PROMPT)
                }
                VoicePickerState.LISTENING_FOR_PICK_CONFIRMATION ->
                  sendAudio(
                    audioData = audioData,
                    nextState = VoicePickerState.SPEAKING_NAVIGATION,
                    recoveryState = VoicePickerState.SPEAKING_WRONG_PICK_CONFIRMATION,
                    shouldRecover = {
                      voicePickerTask?.voicePickingTools?.isAwaitingPickConfirmation() == true
                    },
                  )
                else -> Unit
              }
            },
            onClose = { showRecorder = false },
            autoStart = true,
            autoStopOnSilence = true,
            silenceStopMs = VOICE_PICKER_SILENCE_MS,
            speechAmplitudeThreshold = VOICE_PICKER_SPEECH_THRESHOLD,
          )
        }
      }
    }
  }
}

@Composable
private fun ConversationTranscriptCard(transcript: List<VoicePickerTranscriptLine>) {
  val visibleLines = transcript.filter { it.content.isNotBlank() }
  if (visibleLines.isEmpty()) return
  val scrollState = rememberScrollState()

  // Keep short transcripts compact. Once the card reaches its cap, follow new output to the end.
  LaunchedEffect(visibleLines.size, scrollState.maxValue) {
    if (scrollState.maxValue > 0) scrollState.scrollTo(scrollState.maxValue)
  }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Conversation transcript", style = MaterialTheme.typography.titleMedium)
      Box(modifier = Modifier.fillMaxWidth().heightIn(max = TRANSCRIPT_MAX_HEIGHT)) {
        Column(
          modifier = Modifier.fillMaxWidth().verticalScroll(scrollState).padding(end = 10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          visibleLines.forEach { line ->
            Text("${line.speaker}: ${line.content}")
          }
        }
        if (scrollState.maxValue > 0) {
          Canvas(modifier = Modifier.matchParentSize().align(Alignment.CenterEnd)) {
            val thumbWidth = 4.dp.toPx()
            val minimumThumbHeight = 24.dp.toPx()
            val contentHeight = size.height + scrollState.maxValue
            val thumbHeight =
              (size.height * size.height / contentHeight).coerceAtLeast(minimumThumbHeight)
            val travel = size.height - thumbHeight
            val progress = scrollState.value.toFloat() / scrollState.maxValue
            val thumbTop = travel * progress
            drawRoundRect(
              color = Color.Gray.copy(alpha = 0.65f),
              topLeft = Offset(size.width - thumbWidth, thumbTop),
              size = androidx.compose.ui.geometry.Size(thumbWidth, thumbHeight),
              cornerRadius = CornerRadius(thumbWidth / 2, thumbWidth / 2),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DebugStateCard(
  state: VoicePickerState,
  amplitude: Int,
  model: Model?,
  toolTrace: VoicePickingToolTrace?,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Debug status", style = MaterialTheme.typography.titleMedium)
      Text(state.label, color = MaterialTheme.colorScheme.primary)
      Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("Mic level: $amplitude")
        Text("Speech threshold: $VOICE_PICKER_SPEECH_THRESHOLD")
      }
      Text("Model: ${model?.displayName ?: "No downloaded audio model"}")
      toolTrace?.let { trace ->
        val traceScrollState = rememberScrollState()
        Text("Latest tool call", style = MaterialTheme.typography.titleSmall)
        Box(modifier = Modifier.fillMaxWidth().heightIn(max = TOOL_TRACE_MAX_HEIGHT)) {
          Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(traceScrollState).padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(trace.tool)
            Text("Gemma interpreted: ${trace.interpreted}")
            trace.expected?.let { Text("Expected: $it") }
            trace.accepted?.let { accepted ->
              Text(if (accepted) "Validation: accepted" else "Validation: rejected")
            }
          }
        }
      }
    }
  }
}
