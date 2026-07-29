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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.google.ai.edge.gallery.customtasks.voicepicker.VoicePickerMode
import com.google.ai.edge.gallery.customtasks.voicepicker.VoicePickerTask
import com.google.ai.edge.gallery.customtasks.agentchat.VoicePickingModelCheckpoint
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

private enum class WarehouseItemStatus {
  AVAILABLE,
  CANCELLED,
}

private data class WarehouseItem(
  val id: String,
  val name: String,
  val bay: String,
  val rackPosition: String,
  val level: String,
  val loadTagEnding: String,
  val status: WarehouseItemStatus = WarehouseItemStatus.AVAILABLE,
)

private enum class VoicePickerState(val label: String) {
  SELECTING_MODE("Choose Regular or Fast"),
  PREPARING("Preparing on-device model"),
  WAITING_FOR_START("Ready — say \"Start Job 42\" to start job"),
  SPEAKING_START_ORDER_ERROR("Gemma is explaining the job-start problem"),
  SPEAKING_WAREHOUSE_UPDATE("Speaking warehouse update"),
  SENDING_TO_GEMMA("Sending audio to Gemma"),
  GEMMA_RESPONDING("Gemma is responding"),
  SPEAKING_NAVIGATION("Gemma is speaking the next pickup location"),
  WAITING_FOR_ARRIVAL("Waiting for safe-stop signal"),
  LOCAL_LOCATION_PROMPT("Sound detected — prompting for check digits"),
  LISTENING_FOR_CHECK_DIGITS("Listening for location check digits"),
  SPEAKING_WRONG_CHECK_DIGITS("Gemma is explaining the check-digit mismatch"),
  SPEAKING_ITEM_TASK("Gemma is speaking the equipment load"),
  WAITING_FOR_LOAD_SECURED("Waiting for load-secured signal"),
  LOCAL_TAG_PROMPT("Sound detected — prompting for load-tag digits"),
  LISTENING_FOR_TAG_DIGITS("Listening for load-tag digits"),
  SPEAKING_WRONG_TAG_DIGITS("Gemma is explaining the load-tag mismatch"),
  FAST_SPEAKING_LOCATION("Fast: Speaking the location and check-digit request"),
  FAST_LISTENING_FOR_CHECK_DIGITS("Fast: Listening for location check digits"),
  FAST_SPEAKING_WRONG_CHECK_DIGITS("Fast: Speaking the check-digit mismatch"),
  FAST_SPEAKING_ITEM_TASK("Fast: Speaking the equipment load"),
  FAST_LISTENING_FOR_TAG_DIGITS("Fast: Listening for load-tag digits"),
  FAST_SPEAKING_WRONG_TAG_DIGITS("Fast: Speaking the load-tag mismatch"),
  COMPLETE("Job complete"),
  ERROR("Unable to start Forklift Assistant"),
}

private fun routingContextFor(checkpoint: VoicePickingModelCheckpoint): String =
  """
  Routing context (do not say this aloud): the operator is responding to this exact valid checkpoint.
  State: ${checkpoint.phase}
  Last valid system instruction: ${checkpoint.lastValidInstruction}
  Treat this as the operator's first response to that instruction. Extract values only from the new
  audio clip. Do not infer values from the instruction. Do not mention this routing context.
  """.trimIndent()

/**
 * A compact replayable checkpoint used only after Kotlin rejects a response. It preserves the
 * operator's place in the flow without exposing target digits or retaining the rejected turn.
 */
private fun cleanCheckpointFor(checkpoint: VoicePickingModelCheckpoint): List<Message> =
  listOf(Message.model(checkpoint.lastValidInstruction))

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
  var selectedMode by remember { mutableStateOf<VoicePickerMode?>(null) }
  val model =
    remember(selectedMode, modelUiState.tasks, modelUiState.modelDownloadStatus) {
      if (selectedMode != null) {
        modelManagerViewModel.getAllDownloadedModels().firstOrNull { it.llmSupportAudio }
      } else {
        null
      }
    }
  val modelStatus = model?.let { modelUiState.modelInitializationStatus[it.name]?.status }
  val recorderTrigger by viewModel.openAudioRecorderTrigger.collectAsState()
  var state by remember { mutableStateOf(VoicePickerState.SELECTING_MODE) }
  var amplitude by remember { mutableIntStateOf(0) }
  var showRecorder by remember { mutableStateOf(false) }
  var recorderGeneration by remember { mutableIntStateOf(0) }
  var transcript by remember { mutableStateOf(emptyList<VoicePickerTranscriptLine>()) }
  var nextTranscriptId by remember { mutableIntStateOf(0) }
  var lastToolTrace by remember { mutableStateOf<VoicePickingToolTrace?>(null) }
  var showDebugOutput by remember { mutableStateOf(true) }
  var voiceTurnGeneration by remember { mutableIntStateOf(0) }
  var fastModelResponseFinished by remember { mutableStateOf(true) }
  var fastLocalSpeechStarted by remember { mutableStateOf(false) }
  var fastLocalSpeechFinished by remember { mutableStateOf(false) }
  val warehouseItems =
    remember {
      mutableStateListOf(
        WarehouseItem("951", "Power generator", "12", "3", "2", "951"),
        WarehouseItem("208", "Air compressor", "7", "1", "4", "208"),
        WarehouseItem("664", "Water pump", "3", "6", "1", "664"),
      )
    }

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

  fun resumeListeningAfterSpeech() {
    state =
      when (state) {
        VoicePickerState.SPEAKING_START_ORDER_ERROR -> VoicePickerState.WAITING_FOR_START
        VoicePickerState.SPEAKING_WAREHOUSE_UPDATE ->
          if (selectedMode == VoicePickerMode.FAST) {
            VoicePickerState.FAST_LISTENING_FOR_CHECK_DIGITS
          } else {
            VoicePickerState.WAITING_FOR_ARRIVAL
          }
        VoicePickerState.SPEAKING_NAVIGATION -> VoicePickerState.WAITING_FOR_ARRIVAL
        VoicePickerState.LOCAL_LOCATION_PROMPT -> VoicePickerState.LISTENING_FOR_CHECK_DIGITS
        VoicePickerState.SPEAKING_WRONG_CHECK_DIGITS ->
          VoicePickerState.LISTENING_FOR_CHECK_DIGITS
        VoicePickerState.SPEAKING_ITEM_TASK -> VoicePickerState.WAITING_FOR_LOAD_SECURED
        VoicePickerState.LOCAL_TAG_PROMPT -> VoicePickerState.LISTENING_FOR_TAG_DIGITS
        VoicePickerState.SPEAKING_WRONG_TAG_DIGITS ->
          VoicePickerState.LISTENING_FOR_TAG_DIGITS
        VoicePickerState.FAST_SPEAKING_LOCATION ->
          VoicePickerState.FAST_LISTENING_FOR_CHECK_DIGITS
        VoicePickerState.FAST_SPEAKING_WRONG_CHECK_DIGITS ->
          VoicePickerState.FAST_LISTENING_FOR_CHECK_DIGITS
        VoicePickerState.FAST_SPEAKING_ITEM_TASK ->
          VoicePickerState.FAST_LISTENING_FOR_TAG_DIGITS
        VoicePickerState.FAST_SPEAKING_WRONG_TAG_DIGITS ->
          VoicePickerState.FAST_LISTENING_FOR_TAG_DIGITS
        else -> state
      }
    beginListening()
  }

  LaunchedEffect(selectedMode, model, voiceTask) {
    if (selectedMode == null) return@LaunchedEffect
    when {
      model == null || voiceTask == null -> state = VoicePickerState.ERROR
      else -> modelManagerViewModel.initializeModel(context, voiceTask, model)
    }
  }

  LaunchedEffect(selectedMode, modelStatus) {
    if (selectedMode == null) return@LaunchedEffect
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
      if (
        selectedMode == VoicePickerMode.FAST &&
          !fastModelResponseFinished &&
          fastLocalSpeechStarted
      ) {
        fastLocalSpeechFinished = true
      } else if (selectedMode != VoicePickerMode.FAST || fastModelResponseFinished) {
        resumeListeningAfterSpeech()
      }
    }
  }

  LaunchedEffect(fastModelResponseFinished, fastLocalSpeechFinished) {
    if (
      selectedMode == VoicePickerMode.FAST &&
        fastModelResponseFinished &&
        fastLocalSpeechFinished &&
        modelStatus == ModelInitializationStatusType.INITIALIZED &&
        state != VoicePickerState.COMPLETE &&
        state != VoicePickerState.ERROR
    ) {
      fastLocalSpeechFinished = false
      resumeListeningAfterSpeech()
    }
  }

  DisposableEffect(model, voiceTask) {
    onDispose {
      voicePickerTask?.finishFastModelTurn()
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
    val mode = selectedMode ?: run {
      state = VoicePickerState.ERROR
      return
    }
    selectedTask.syncCancelledWarehouseItems(
      mode,
      warehouseItems
        .filter { it.status == WarehouseItemStatus.CANCELLED }
        .map { it.loadTagEnding }
        .toSet()
    )
    val turnGeneration = voiceTurnGeneration
    val checkpoint = selectedTask.getModelCheckpoint(mode)
    val routingContext = routingContextFor(checkpoint)
    val isFastMode = mode == VoicePickerMode.FAST
    var fastToolResultHandled = false
    var resolvedFastState: VoicePickerState? = null

    fun resolveState(toolCalled: Boolean): VoicePickerState {
      val shouldEnterRecovery = recoveryState != null && (!toolCalled || shouldRecover())
      return when {
        selectedTask.isComplete(mode) -> VoicePickerState.COMPLETE
        shouldEnterRecovery -> requireNotNull(recoveryState)
        else -> nextState
      }
    }

    state = VoicePickerState.SENDING_TO_GEMMA
    addTranscriptLine("Operator audio", "Sent to Gemma")
    val agentLineId = addTranscriptLine("Agent", "")
    if (isFastMode) {
      fastModelResponseFinished = false
      fastLocalSpeechStarted = false
      fastLocalSpeechFinished = false
      selectedTask.beginFastModelTurn { workerMessage ->
        if (turnGeneration == voiceTurnGeneration) {
          fastToolResultHandled = true
          lastToolTrace = selectedTask.getLastToolTrace(mode)
          val resolvedState = resolveState(toolCalled = true)
          resolvedFastState = resolvedState
          state = resolvedState
          transcript =
            transcript.map { line ->
              if (line.id == agentLineId) line.copy(content = workerMessage) else line
            }
          fastLocalSpeechStarted = true
          // Fast ends at the tool call. Do not feed the result back through another model pass.
          viewModel.stopInferenceAfterToolCall(selectedModel)
          viewModel.speakLocalPrompt(workerMessage)
        }
      }
      // Fast mode speaks the deterministic Kotlin result directly. Raw model text stays muted.
      viewModel.setSpeakReplies(false)
    }
    fun runInference() {
      viewModel.generateResponse(
        model = selectedModel,
        input = routingContext,
        audioMessages = listOf(ChatMessageAudioClip(audioData, VOICE_PICKER_SAMPLE_RATE, ChatSide.USER)),
        onFirstToken = {
          if (
            turnGeneration == voiceTurnGeneration && (!isFastMode || !fastToolResultHandled)
          ) {
            state = VoicePickerState.GEMMA_RESPONDING
          }
        },
        onResponseDelta = { delta ->
          if (turnGeneration != voiceTurnGeneration) return@generateResponse
          if (!isFastMode) {
            transcript =
              transcript.map { line ->
                if (line.id == agentLineId) line.copy(content = line.content + delta) else line
              }
          }
        },
        onDone = {
          if (turnGeneration != voiceTurnGeneration) return@generateResponse
          if (isFastMode) {
            selectedTask.finishFastModelTurn()
            fastModelResponseFinished = true
            viewModel.setSpeakReplies(true)
            if (fastToolResultHandled) {
              resolvedFastState?.let { state = it }
            } else {
              lastToolTrace = null
              state = resolveState(toolCalled = false)
              val fallback = "I didn't catch that. ${checkpoint.lastValidInstruction}"
              transcript =
                transcript.map { line ->
                  if (line.id == agentLineId) line.copy(content = fallback) else line
                }
              fastLocalSpeechStarted = true
              viewModel.speakLocalPrompt(fallback)
            }
          } else {
            lastToolTrace = selectedTask.getLastToolTrace(mode)
            state = resolveState(toolCalled = true)
          }
        },
        onError = {
          if (turnGeneration == voiceTurnGeneration) {
            if (isFastMode) {
              selectedTask.finishFastModelTurn()
              fastModelResponseFinished = true
              viewModel.setSpeakReplies(true)
            }
            state = VoicePickerState.ERROR
          }
        },
      )
    }
    // Rebuild a tiny, deterministic conversation for every model turn. The checkpoint advances
    // only on a valid Kotlin transition, so failures can never enter Gemma's context.
    viewModel.resetConversationForFreshTurn(
      model = selectedModel,
      systemInstruction = selectedTask.conversationSystemInstruction(mode),
      tools = selectedTask.conversationTools(mode),
      initialMessages = cleanCheckpointFor(checkpoint),
      onDone = ::runInference,
    )
  }

  fun speakLocalPrompt(
    prompt: String,
    speakingState: VoicePickerState,
  ) {
    state = speakingState
    addTranscriptLine("Agent", prompt)
    viewModel.speakLocalPrompt(prompt)
  }

  fun handleWarehouseCancellation(item: WarehouseItem) {
    val mode = selectedMode ?: return
    val selectedTask = voicePickerTask ?: return
    val result = selectedTask.cancelWarehouseItem(mode, item.loadTagEnding)
    if (!result.interruptedActivePick) return

    voiceTurnGeneration++
    showRecorder = false
    model?.let { viewModel.interruptForWarehouseUpdate(it) }
    if (mode == VoicePickerMode.FAST) {
      selectedTask.finishFastModelTurn()
      fastModelResponseFinished = true
      fastLocalSpeechStarted = false
      fastLocalSpeechFinished = false
    }
    val message = result.workerMessage ?: return
    val orderComplete = selectedTask.isComplete(mode)
    state = if (orderComplete) VoicePickerState.COMPLETE else VoicePickerState.SPEAKING_WAREHOUSE_UPDATE
    addTranscriptLine("Agent", message)
    if (mode == VoicePickerMode.FAST) viewModel.setSpeakReplies(true)
    viewModel.speakLocalPrompt(message)
  }

  Scaffold(
    topBar = {
      GalleryTopAppBar(
        title = "Forklift Assistant",
        leftAction = AppBarAction(AppBarActionType.NAVIGATE_UP, onNavigateUp),
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp).padding(innerPadding),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Forklift Assistant", style = MaterialTheme.typography.headlineMedium)
      if (selectedMode == null) {
        Text("Choose a mode", style = MaterialTheme.typography.titleMedium)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Button(
            modifier = Modifier.weight(1f),
            onClick = {
              voicePickerTask?.prepareMode(VoicePickerMode.REGULAR)
              state = VoicePickerState.PREPARING
              selectedMode = VoicePickerMode.REGULAR
            },
          ) {
            Text("Regular")
          }
          Button(
            modifier = Modifier.weight(1f),
            onClick = {
              voicePickerTask?.prepareMode(VoicePickerMode.FAST)
              state = VoicePickerState.PREPARING
              selectedMode = VoicePickerMode.FAST
            },
          ) {
            Text("Fast")
          }
        }
      }
      if (selectedMode != null && state == VoicePickerState.WAITING_FOR_START) {
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
          Text(
            text = "Say \"Start Job 42\" to start job",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
      }
      if (selectedMode != null) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text("Debug output", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
          Switch(checked = showDebugOutput, onCheckedChange = { showDebugOutput = it })
        }
      }
      if (selectedMode != null && showDebugOutput) {
        DebugStateCard(state = state, amplitude = amplitude, model = model, toolTrace = lastToolTrace)
      }
      if (selectedMode != null) {
        WarehouseLoadsPanel(
          items = warehouseItems,
          onCancel = { item ->
            val index = warehouseItems.indexOfFirst { it.id == item.id }
            if (index >= 0) warehouseItems[index] = item.copy(status = WarehouseItemStatus.CANCELLED)
            handleWarehouseCancellation(item)
          },
        )
      }
      if (selectedMode != null && showRecorder && voiceTask != null) {
        key(recorderGeneration) {
          AudioRecorderPanel(
            task = voiceTask,
            onAmplitudeChanged = { amplitude = it },
            onSendAudioClip = { audioData ->
              showRecorder = false
              when (state) {
                VoicePickerState.WAITING_FOR_START -> {
                  selectedMode?.let { mode ->
                    sendAudio(
                      audioData = audioData,
                      nextState =
                        if (mode == VoicePickerMode.FAST) {
                          VoicePickerState.FAST_SPEAKING_LOCATION
                        } else {
                          VoicePickerState.SPEAKING_NAVIGATION
                        },
                      recoveryState = VoicePickerState.SPEAKING_START_ORDER_ERROR,
                      shouldRecover = {
                        voicePickerTask?.isAwaitingStartOrder(mode) == true
                      },
                    )
                  }
                }
                VoicePickerState.WAITING_FOR_ARRIVAL -> {
                  val prompt = voicePickerTask?.voicePickingTools?.confirmArrival()?.get("sayText") as? String
                  if (prompt != null) speakLocalPrompt(prompt, VoicePickerState.LOCAL_LOCATION_PROMPT)
                }
                VoicePickerState.LISTENING_FOR_CHECK_DIGITS ->
                  sendAudio(
                    audioData = audioData,
                    nextState = VoicePickerState.SPEAKING_ITEM_TASK,
                    recoveryState = VoicePickerState.SPEAKING_WRONG_CHECK_DIGITS,
                    shouldRecover = {
                      voicePickerTask?.isAwaitingCheckDigits(VoicePickerMode.REGULAR) == true
                    },
                  )
                VoicePickerState.WAITING_FOR_LOAD_SECURED -> {
                  val prompt = voicePickerTask?.voicePickingTools?.confirmItemLocated()?.get("sayText") as? String
                  if (prompt != null) {
                    speakLocalPrompt(
                      prompt = prompt,
                      speakingState = VoicePickerState.LOCAL_TAG_PROMPT,
                    )
                  }
                }
                VoicePickerState.LISTENING_FOR_TAG_DIGITS ->
                  sendAudio(
                    audioData = audioData,
                    nextState = VoicePickerState.SPEAKING_NAVIGATION,
                    recoveryState = VoicePickerState.SPEAKING_WRONG_TAG_DIGITS,
                    shouldRecover = {
                      voicePickerTask?.isAwaitingTagConfirmation(VoicePickerMode.REGULAR) == true
                    },
                  )
                VoicePickerState.FAST_LISTENING_FOR_CHECK_DIGITS ->
                  sendAudio(
                    audioData = audioData,
                    nextState = VoicePickerState.FAST_SPEAKING_ITEM_TASK,
                    recoveryState = VoicePickerState.FAST_SPEAKING_WRONG_CHECK_DIGITS,
                    shouldRecover = {
                      voicePickerTask?.isAwaitingCheckDigits(VoicePickerMode.FAST) == true
                    },
                  )
                VoicePickerState.FAST_LISTENING_FOR_TAG_DIGITS ->
                  sendAudio(
                    audioData = audioData,
                    nextState = VoicePickerState.FAST_SPEAKING_LOCATION,
                    recoveryState = VoicePickerState.FAST_SPEAKING_WRONG_TAG_DIGITS,
                    shouldRecover = {
                      voicePickerTask?.isAwaitingTagConfirmation(VoicePickerMode.FAST) == true
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
private fun WarehouseLoadsPanel(items: List<WarehouseItem>, onCancel: (WarehouseItem) -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("Warehouse Loads (Admin View)", style = MaterialTheme.typography.titleMedium)
      if (items.isEmpty()) {
        Text("No equipment loads in warehouse")
      } else {
        items.forEachIndexed { index, item ->
          if (index > 0) HorizontalDivider()
          Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
              Text(
                "Bay ${item.bay}  |  Rack ${item.rackPosition}  |  Level ${item.level}",
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                "${item.name}  |  Tag ${item.loadTagEnding}",
                style = MaterialTheme.typography.bodyMedium,
              )
            }
            if (item.status == WarehouseItemStatus.CANCELLED) {
              Text("CANCELLED", style = MaterialTheme.typography.labelSmall)
            } else {
              TextButton(onClick = { onCancel(item) }) { Text("×") }
            }
          }
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
