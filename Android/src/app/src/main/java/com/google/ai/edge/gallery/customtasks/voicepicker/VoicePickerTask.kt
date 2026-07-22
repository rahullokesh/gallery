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
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.agentchat.VoicePickingTools
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope

/** Registers the dedicated Voice Picker page on the Gallery home screen. */
class VoicePickerTask : CustomTask {
  val voicePickingTools = VoicePickingTools()
  override val task =
    Task(
      id = VOICE_PICKER_TASK_ID,
      label = "Voice Picker",
      category = Category.LLM,
      icon = Icons.Outlined.Mic,
      description = "A hands-free warehouse voice-picking experience.",
      shortDescription = "Pick warehouse orders by voice",
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
    voicePickingTools.reset()
    LlmChatModelHelper.initialize(
      context = context,
      model = model,
      taskId = task.id,
      supportImage = false,
      supportAudio = true,
      onDone = onDone,
      systemInstruction = Contents.of(VOICE_PICKER_SYSTEM_PROMPT),
      tools = listOf(tool(voicePickingTools)),
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
  You are a warehouse voice-picking assistant. Route every worker utterance to exactly one
  voice-picking tool. Arrival phrases such as "I'm here" call confirm_arrival; item-location
  phrases such as "I found it" call confirm_item_located. Three digits alone verify a location,
  and item digits plus a quantity confirm a pick. After a tool returns, reply with exactly its
  sayText value and nothing else.
  Never invent warehouse data, items, quantities, or locations.
  """

@Module
@InstallIn(SingletonComponent::class)
internal object VoicePickerTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask = VoicePickerTask()
}
