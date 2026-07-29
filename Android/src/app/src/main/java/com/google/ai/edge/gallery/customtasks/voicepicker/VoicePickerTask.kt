/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.customtasks.voicepicker

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.agentchat.FastVoicePickingTools
import com.google.ai.edge.gallery.customtasks.agentchat.VoicePickingTools
import com.google.ai.edge.gallery.customtasks.agentchat.VoicePickingModelCheckpoint
import com.google.ai.edge.gallery.customtasks.agentchat.VoicePickingToolTrace
import com.google.ai.edge.gallery.customtasks.agentchat.WarehouseCancellationResult
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import com.google.ai.edge.litertlm.ToolProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope

enum class VoicePickerMode {
  REGULAR,
  FAST,
}

/** Registers the dedicated Forklift Assistant page on the Gallery home screen. */
class VoicePickerTask : CustomTask {
  val voicePickingTools = VoicePickingTools()
  val fastVoicePickingTools = FastVoicePickingTools()
  @Volatile private var activeMode = VoicePickerMode.REGULAR

  fun prepareMode(mode: VoicePickerMode) {
    activeMode = mode
    resetStateMachine(mode)
  }

  fun conversationSystemInstruction(mode: VoicePickerMode = activeMode): Contents =
    Contents.of(
      when (mode) {
        VoicePickerMode.REGULAR -> VOICE_PICKER_SYSTEM_PROMPT
        VoicePickerMode.FAST -> FAST_VOICE_PICKER_SYSTEM_PROMPT
      }
    )

  fun conversationTools(mode: VoicePickerMode = activeMode): List<ToolProvider> =
    when (mode) {
      VoicePickerMode.REGULAR -> listOf(tool(voicePickingTools))
      VoicePickerMode.FAST -> listOf(tool(fastVoicePickingTools))
    }

  fun resetStateMachine(mode: VoicePickerMode) {
    when (mode) {
      VoicePickerMode.REGULAR -> voicePickingTools.reset()
      VoicePickerMode.FAST -> fastVoicePickingTools.reset()
    }
  }

  fun syncCancelledWarehouseItems(mode: VoicePickerMode, itemLast3s: Set<String>) {
    when (mode) {
      VoicePickerMode.REGULAR -> voicePickingTools.syncCancelledWarehouseItems(itemLast3s)
      VoicePickerMode.FAST -> fastVoicePickingTools.syncCancelledWarehouseItems(itemLast3s)
    }
  }

  fun getModelCheckpoint(mode: VoicePickerMode): VoicePickingModelCheckpoint =
    when (mode) {
      VoicePickerMode.REGULAR -> voicePickingTools.getModelCheckpoint()
      VoicePickerMode.FAST -> fastVoicePickingTools.getModelCheckpoint()
    }

  fun getLastToolTrace(mode: VoicePickerMode): VoicePickingToolTrace? =
    when (mode) {
      VoicePickerMode.REGULAR -> voicePickingTools.getLastToolTrace()
      VoicePickerMode.FAST -> fastVoicePickingTools.getLastToolTrace()
    }

  fun isComplete(mode: VoicePickerMode): Boolean =
    when (mode) {
      VoicePickerMode.REGULAR -> voicePickingTools.isComplete()
      VoicePickerMode.FAST -> fastVoicePickingTools.isComplete()
    }

  fun isAwaitingStartOrder(mode: VoicePickerMode): Boolean =
    when (mode) {
      VoicePickerMode.REGULAR -> voicePickingTools.isAwaitingStartOrder()
      VoicePickerMode.FAST -> fastVoicePickingTools.isAwaitingStartOrder()
    }

  fun isAwaitingCheckDigits(mode: VoicePickerMode): Boolean =
    when (mode) {
      VoicePickerMode.REGULAR -> voicePickingTools.isAwaitingCheckDigits()
      VoicePickerMode.FAST -> fastVoicePickingTools.isAwaitingCheckDigits()
    }

  fun isAwaitingTagConfirmation(mode: VoicePickerMode): Boolean =
    when (mode) {
      VoicePickerMode.REGULAR -> voicePickingTools.isAwaitingTagConfirmation()
      VoicePickerMode.FAST -> fastVoicePickingTools.isAwaitingTagConfirmation()
    }

  fun beginFastModelTurn(onSayText: ((String) -> Unit)? = null) =
    fastVoicePickingTools.beginModelTurn(onSayText)

  fun finishFastModelTurn() = fastVoicePickingTools.finishModelTurn()

  fun wasFastToolCalledThisTurn(): Boolean = fastVoicePickingTools.wasToolCalledThisTurn()

  fun getFastLastSayText(): String = fastVoicePickingTools.getLastSayText()

  fun cancelWarehouseItem(
    mode: VoicePickerMode,
    itemLast3: String,
  ): WarehouseCancellationResult =
    when (mode) {
      VoicePickerMode.REGULAR -> voicePickingTools.cancelWarehouseItem(itemLast3)
      VoicePickerMode.FAST -> fastVoicePickingTools.cancelWarehouseItem(itemLast3)
    }

  override val task =
    Task(
      id = VOICE_PICKER_TASK_ID,
      label = "Forklift Assistant",
      category = Category.LLM,
      icon = Icons.Outlined.Mic,
      description = "A hands-free forklift pickup assistant for warehouse equipment.",
      shortDescription = "Move equipment by voice",
      models = mutableListOf(),
      experimental = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    val mode = activeMode
    resetStateMachine(mode)
    LlmChatModelHelper.initialize(
      context = context,
      model = model,
      taskId = task.id,
      supportImage = false,
      supportAudio = true,
      onDone = onDone,
      systemInstruction = conversationSystemInstruction(mode),
      tools = conversationTools(mode),
      enableConversationConstrainedDecoding = true,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) = LlmChatModelHelper.cleanUp(model = model, onDone = onDone)

  @Composable override fun MainScreen(data: Any) {}
}

const val VOICE_PICKER_TASK_ID = "voice_picker"

private const val VOICE_PICKER_SYSTEM_PROMPT =
  """
  You are a warehouse forklift pickup assistant. Route every operator utterance to exactly one
  forklift tool. Start-job phrases call start_order. Safe-stop phrases such as "I'm stopped" or
  "I'm in position" call confirm_arrival. Load-secured phrases such as "load secure" or "I have
  it" call confirm_item_located. In AWAITING_CHECK_DIGITS, three digits call
  verify_check_digits. In AWAITING_TAG_CONFIRMATION, three digits call confirm_pick. After a tool
  returns, reply with exactly its sayText value and nothing else.
  Treat the routing context included with each new audio clip as authoritative. Extract numeric
  values only from that new audio clip; never substitute values from a previous instruction,
  correction, or conversation turn.
  Never invent warehouse data, equipment, load tags, or locations.
  """

private const val FAST_VOICE_PICKER_SYSTEM_PROMPT =
  """
  You are a tool-call router for a condensed warehouse forklift pickup workflow. You are not a
  conversational assistant or a transcription service. Never repeat, quote, paraphrase,
  acknowledge, or transcribe the operator's audio. Your first and only action for every operator
  utterance must be exactly one tool call. Never output plain text before calling a tool.
  Start-job phrases call start_order. In the
  AWAITING_CHECK_DIGITS state, three digits call verify_check_digits. In the
  AWAITING_TAG_CONFIRMATION state, three digits call confirm_pick. Repeat requests call
  repeat_instruction. If the audio is unclear or does not match the expected response for the
  current state, call repeat_instruction instead of guessing or producing text.
  End the turn after making the tool call. Do not generate a natural-language response; Android
  speaks the deterministic tool result directly. Treat the routing context included with each new
  audio clip as authoritative. Extract numeric values only from that new audio clip; never
  substitute values from an instruction, correction, or previous turn. Never independently decide
  whether an answer is correct. Kotlin performs all validation. Never invent warehouse data,
  equipment, load tags, or locations.
  """

@Module
@InstallIn(SingletonComponent::class)
internal object VoicePickerTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask = VoicePickerTask()
}
