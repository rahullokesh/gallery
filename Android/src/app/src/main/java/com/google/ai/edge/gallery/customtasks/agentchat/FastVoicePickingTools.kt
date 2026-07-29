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
package com.google.ai.edge.gallery.customtasks.agentchat

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Condensed forklift pickup state machine used only by Fast mode.
 *
 * Unlike [VoicePickingTools], this flow has no separate safe-stop or load-secured gate. A
 * successful job start immediately requests location check digits, and the operator reads the
 * load-tag digits when the equipment is secure.
 */
class FastVoicePickingTools : ToolSet {

  private enum class Phase {
    NOT_STARTED,
    AWAITING_CHECK_DIGITS,
    AWAITING_TAG_CONFIRMATION,
    COMPLETE,
  }

  private var order: MockOrder? = null
  private var pickIndex = 0
  private var phase = Phase.NOT_STARTED
  private var lastSayText = SIGN_ON_PROMPT
  private var lastValidInstruction = SIGN_ON_PROMPT
  private var lastToolTrace: VoicePickingToolTrace? = null
  private var toolCalledThisTurn = false
  private var toolResultDeliveredThisTurn = false
  private var onToolSayText: ((String) -> Unit)? = null
  private val cancelledItemLast3 = mutableSetOf<String>()

  private val currentPick: Pick?
    get() = order?.picks?.getOrNull(pickIndex)

  @Synchronized
  fun reset() {
    order = null
    pickIndex = 0
    phase = Phase.NOT_STARTED
    lastSayText = SIGN_ON_PROMPT
    lastValidInstruction = SIGN_ON_PROMPT
    lastToolTrace = null
    toolCalledThisTurn = false
    toolResultDeliveredThisTurn = false
    onToolSayText = null
    cancelledItemLast3.clear()
  }

  /** Clears per-inference evidence so the caller can detect malformed model-only responses. */
  @Synchronized
  fun beginModelTurn(onSayText: ((String) -> Unit)? = null) {
    toolCalledThisTurn = false
    toolResultDeliveredThisTurn = false
    lastToolTrace = null
    onToolSayText = onSayText
  }

  @Synchronized
  fun finishModelTurn() {
    onToolSayText = null
  }

  @Synchronized
  fun wasToolCalledThisTurn(): Boolean = toolCalledThisTurn

  @Synchronized
  fun getLastSayText(): String = lastSayText

  @Synchronized
  @Tool(
    description =
      "Start a fast forklift pickup job. Call when the operator says 'start job' followed by a " +
        "job number. After the tool returns, reply with exactly its sayText, verbatim."
  )
  fun startOrder(
    @ToolParam(description = "The spoken job number, digits only.") orderNumber: String
  ): Map<String, Any> {
    toolCalledThisTurn = true
    if (phase != Phase.NOT_STARTED) {
      lastToolTrace = VoicePickingToolTrace("start_order", "job ${orderNumber.digitsOnly()}", accepted = false)
      return say(lastValidInstruction)
    }
    val normalized = orderNumber.digitsOnly()
    val matched = MOCK_ORDERS.find { it.orderNumber == normalized }
    if (matched == null) {
      lastToolTrace = VoicePickingToolTrace("start_order", "job $normalized", accepted = false)
      return say("Job ${spellDigits(normalized.ifEmpty { orderNumber })} not found. $SIGN_ON_PROMPT")
    }
    lastToolTrace = VoicePickingToolTrace("start_order", "job $normalized", accepted = true)
    order = matched
    pickIndex = 0
    advanceToNextPendingPick()
    val pick = currentPick
    if (pick == null) {
      phase = Phase.COMPLETE
      return advance("Job ${spellDigits(matched.orderNumber)} has no remaining moves.")
    }
    phase = Phase.AWAITING_CHECK_DIGITS
    return advance(
      "Job ${spellDigits(matched.orderNumber)} started, ${matched.picks.size} moves. " +
        "Proceed to ${pick.locatorSpoken}. When safely stopped, read the 3 check digits on the " +
        "location label."
    )
  }

  @Synchronized
  @Tool(
    description =
      "Verify the operator is at the right pickup location. Call when the operator says 3 location check " +
        "digits. After the tool returns, reply with exactly its sayText, verbatim."
  )
  fun verifyCheckDigits(
    @ToolParam(description = "The 3 spoken check digits, digits only.") checkDigits: String
  ): Map<String, Any> {
    toolCalledThisTurn = true
    val pick = currentPick ?: return notInSession()
    if (phase != Phase.AWAITING_CHECK_DIGITS) {
      lastToolTrace = VoicePickingToolTrace("verify_check_digits", checkDigits.digitsOnly(), accepted = false)
      return say(lastSayText)
    }
    val heardDigits = checkDigits.digitsOnly()
    if (heardDigits != pick.checkDigits) {
      lastToolTrace =
        VoicePickingToolTrace(
          tool = "verify_check_digits",
          interpreted = heardDigits,
          expected = pick.checkDigits,
          accepted = false,
        )
      return say(
        "Wrong check digits. You should be at ${pick.locatorSpoken}. " +
          "Read the 3 digits printed on the location label."
      )
    }
    lastToolTrace =
      VoicePickingToolTrace(
        tool = "verify_check_digits",
        interpreted = heardDigits,
        expected = pick.checkDigits,
        accepted = true,
      )
    phase = Phase.AWAITING_TAG_CONFIRMATION
    return advanceWithModelCheckpoint(
      text =
        "Pickup location confirmed. Lift the ${pick.itemName} load, tag ending " +
          "${spellDigits(pick.itemLast3)}. When the load is secure, read the last 3 digits on the " +
          "load tag.",
      modelCheckpoint = "When the load is secure, read the last 3 digits on the load tag.",
    )
  }

  @Synchronized
  @Tool(
    description =
      "Verify a secured fast-mode equipment load. In AWAITING_TAG_CONFIRMATION, call when the " +
        "operator reads the last 3 load-tag digits. After the tool returns, reply with exactly " +
        "its sayText, verbatim."
  )
  fun confirmPick(
    @ToolParam(description = "The last 3 spoken load-tag digits, digits only.")
    itemDigits: String,
  ): Map<String, Any> {
    toolCalledThisTurn = true
    val curOrder = order ?: return notInSession()
    val pick = currentPick ?: return notInSession()
    if (phase != Phase.AWAITING_TAG_CONFIRMATION) {
      lastToolTrace =
        VoicePickingToolTrace("confirm_pick", itemDigits.digitsOnly(), accepted = false)
      return say(lastSayText)
    }
    val heardItemDigits = itemDigits.digitsOnly()
    if (heardItemDigits != pick.itemLast3) {
      lastToolTrace =
        VoicePickingToolTrace(
          tool = "confirm_pick",
          interpreted = heardItemDigits,
          expected = pick.itemLast3,
          accepted = false,
        )
      return say("Wrong load tag. Read the last 3 digits on the load tag and try again.")
    }
    lastToolTrace =
      VoicePickingToolTrace(
        tool = "confirm_pick",
        interpreted = heardItemDigits,
        expected = pick.itemLast3,
        accepted = true,
      )
    pickIndex++
    advanceToNextPendingPick()
    val next = currentPick
    if (next == null) {
      phase = Phase.COMPLETE
      return advance("Pickup confirmed. Job ${spellDigits(curOrder.orderNumber)} complete. Nice work.")
    }
    phase = Phase.AWAITING_CHECK_DIGITS
    return advance(
      "Pickup confirmed. Next, proceed to ${next.locatorSpoken}. When safely stopped, read the 3 " +
        "check digits on the location label."
    )
  }

  @Synchronized
  fun isComplete(): Boolean = phase == Phase.COMPLETE

  @Synchronized
  fun isAwaitingStartOrder(): Boolean = phase == Phase.NOT_STARTED

  @Synchronized
  fun isAwaitingCheckDigits(): Boolean = phase == Phase.AWAITING_CHECK_DIGITS

  @Synchronized
  fun isAwaitingTagConfirmation(): Boolean = phase == Phase.AWAITING_TAG_CONFIRMATION

  @Synchronized
  fun getLastToolTrace(): VoicePickingToolTrace? = lastToolTrace

  @Synchronized
  fun getModelCheckpoint(): VoicePickingModelCheckpoint =
    VoicePickingModelCheckpoint(phase = phase.name, lastValidInstruction = lastValidInstruction)

  @Synchronized
  fun syncCancelledWarehouseItems(itemLast3s: Set<String>) {
    cancelledItemLast3.clear()
    cancelledItemLast3.addAll(itemLast3s)
  }

  @Synchronized
  fun cancelWarehouseItem(itemLast3: String): WarehouseCancellationResult {
    val curOrder = order ?: return WarehouseCancellationResult(interruptedActivePick = false)
    val cancelledPick = curOrder.picks.find { it.itemLast3 == itemLast3 }
      ?: return WarehouseCancellationResult(interruptedActivePick = false)
    if (!cancelledItemLast3.add(itemLast3)) {
      return WarehouseCancellationResult(interruptedActivePick = false)
    }

    val isActivePick = currentPick?.itemLast3 == itemLast3 && phase != Phase.COMPLETE
    if (!isActivePick) return WarehouseCancellationResult(interruptedActivePick = false)

    pickIndex++
    advanceToNextPendingPick()
    val next = currentPick
    if (next == null) {
      phase = Phase.COMPLETE
      val message =
        "Warehouse update. The ${cancelledPick.itemName} load is no longer required. " +
          "Job ${spellDigits(curOrder.orderNumber)} is complete. Nice work."
      advance(message)
      return WarehouseCancellationResult(interruptedActivePick = true, workerMessage = message)
    }

    phase = Phase.AWAITING_CHECK_DIGITS
    val nextInstruction =
      "Proceed to ${next.locatorSpoken}. When safely stopped, read the 3 check digits on the " +
        "location label."
    val message =
      "Warehouse update. The ${cancelledPick.itemName} load is no longer required. " +
        "Next, $nextInstruction"
    lastValidInstruction = nextInstruction
    say(message)
    return WarehouseCancellationResult(interruptedActivePick = true, workerMessage = message)
  }

  @Synchronized
  @Tool(
    description =
      "Repeat the current instruction. Call when the operator says 'repeat' or 'say again', or when " +
        "their utterance doesn't match another fast forklift tool. After the tool returns, reply " +
        "with exactly its sayText, verbatim."
  )
  fun repeatInstruction(): Map<String, Any> {
    toolCalledThisTurn = true
    lastToolTrace = VoicePickingToolTrace("repeat_instruction", "repeat", accepted = true)
    return say(lastValidInstruction)
  }

  private fun advance(text: String): Map<String, Any> {
    lastValidInstruction = text
    return say(text)
  }

  private fun advanceWithModelCheckpoint(
    text: String,
    modelCheckpoint: String,
  ): Map<String, Any> {
    lastValidInstruction = modelCheckpoint
    return say(text)
  }

  private fun advanceToNextPendingPick() {
    val picks = order?.picks ?: return
    while (pickIndex < picks.size && picks[pickIndex].itemLast3 in cancelledItemLast3) {
      pickIndex++
    }
  }

  private fun say(text: String): Map<String, Any> {
    lastSayText = text
    val result =
      mapOf(
        KEY_SAY to text,
        "status" to "succeeded",
        "instruction" to
          "Your entire reply must be EXACTLY the sayText value above, word for word. Do not " +
            "paraphrase, narrate, or add anything.",
      )
    if (!toolResultDeliveredThisTurn) {
      toolResultDeliveredThisTurn = true
      onToolSayText?.invoke(text)
    }
    return result
  }

  private fun notInSession(): Map<String, Any> = say(SIGN_ON_PROMPT)

  companion object {
    private const val KEY_SAY = "sayText"
    private const val SIGN_ON_PROMPT =
      "Say start job, followed by the job number, to begin forklift pickup. For example: start job 4 2."
  }
}
