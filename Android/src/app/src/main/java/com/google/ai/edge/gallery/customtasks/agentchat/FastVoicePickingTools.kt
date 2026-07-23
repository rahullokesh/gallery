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
 * Condensed voice-picking state machine used only by Fast mode.
 *
 * Unlike [VoicePickingTools], this flow has no arrival or item-location phases. A successful order
 * start immediately requests location check digits, and successful check digits immediately begin
 * pick confirmation.
 */
class FastVoicePickingTools : ToolSet {

  private enum class Phase {
    NOT_STARTED,
    AWAITING_CHECK_DIGITS,
    AWAITING_PICK_CONFIRM,
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
      "Start a fast warehouse picking session. Call when the worker says 'start order' followed " +
        "by an order number. After the tool returns, reply with exactly its sayText, verbatim."
  )
  fun startOrder(
    @ToolParam(description = "The spoken order number, digits only.") orderNumber: String
  ): Map<String, Any> {
    toolCalledThisTurn = true
    if (phase != Phase.NOT_STARTED) {
      lastToolTrace = VoicePickingToolTrace("start_order", "order ${orderNumber.digitsOnly()}", accepted = false)
      return say(lastValidInstruction)
    }
    val normalized = orderNumber.digitsOnly()
    val matched = MOCK_ORDERS.find { it.orderNumber == normalized }
    if (matched == null) {
      lastToolTrace = VoicePickingToolTrace("start_order", "order $normalized", accepted = false)
      return say("Order ${spellDigits(normalized.ifEmpty { orderNumber })} not found. $SIGN_ON_PROMPT")
    }
    lastToolTrace = VoicePickingToolTrace("start_order", "order $normalized", accepted = true)
    order = matched
    pickIndex = 0
    advanceToNextPendingPick()
    val pick = currentPick
    if (pick == null) {
      phase = Phase.COMPLETE
      return advance("Order ${spellDigits(matched.orderNumber)} has no remaining picks.")
    }
    phase = Phase.AWAITING_CHECK_DIGITS
    return advance(
      "Order ${spellDigits(matched.orderNumber)} started, ${matched.picks.size} picks. " +
        "Go to ${pick.locatorSpoken}. Read the 3 check digits on the location label."
    )
  }

  @Synchronized
  @Tool(
    description =
      "Verify the worker is at the right location. Call when the worker says 3 location check " +
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
    phase = Phase.AWAITING_PICK_CONFIRM
    val workerItemName = pick.itemName.replace("USB C", "USB-C")
    return advance(
      "Location confirmed. Pick ${pick.quantity} $workerItemName, item ending " +
        "${spellDigits(pick.itemLast3)}."
    )
  }

  @Synchronized
  @Tool(
    description =
      "Confirm a completed fast pick. Call when the worker says 3 item digits plus the quantity " +
        "picked. After the tool returns, reply with exactly its sayText, verbatim."
  )
  fun confirmPick(
    @ToolParam(description = "The last 3 digits of the picked item, digits only.")
    itemDigits: String,
    @ToolParam(description = "How many units the worker picked.") quantity: Int,
  ): Map<String, Any> {
    toolCalledThisTurn = true
    val curOrder = order ?: return notInSession()
    val pick = currentPick ?: return notInSession()
    if (phase != Phase.AWAITING_PICK_CONFIRM) {
      lastToolTrace =
        VoicePickingToolTrace("confirm_pick", "${itemDigits.digitsOnly()}, quantity $quantity", accepted = false)
      return say(lastSayText)
    }
    val heardItemDigits = itemDigits.digitsOnly()
    if (heardItemDigits != pick.itemLast3) {
      lastToolTrace =
        VoicePickingToolTrace(
          tool = "confirm_pick",
          interpreted = "$heardItemDigits, quantity $quantity",
          expected = "${pick.itemLast3}, quantity ${pick.quantity}",
          accepted = false,
        )
      return say(
        "Wrong item. You need the item ending ${spellDigits(pick.itemLast3)}. Check the label and " +
          "try again."
      )
    }
    if (quantity != pick.quantity) {
      lastToolTrace =
        VoicePickingToolTrace(
          tool = "confirm_pick",
          interpreted = "$heardItemDigits, quantity $quantity",
          expected = "${pick.itemLast3}, quantity ${pick.quantity}",
          accepted = false,
        )
      return say(
        "Quantity should be ${pick.quantity}, you said $quantity. Put the extra back or pick the " +
          "rest, then say the item digits and quantity again."
      )
    }
    lastToolTrace =
      VoicePickingToolTrace(
        tool = "confirm_pick",
        interpreted = "$heardItemDigits, quantity $quantity",
        expected = "${pick.itemLast3}, quantity ${pick.quantity}",
        accepted = true,
      )
    pickIndex++
    advanceToNextPendingPick()
    val next = currentPick
    if (next == null) {
      phase = Phase.COMPLETE
      return advance(
        "Pick confirmed. Order ${spellDigits(curOrder.orderNumber)} complete. Deliver to packing " +
          "station ${curOrder.packingStation}. Nice work."
      )
    }
    phase = Phase.AWAITING_CHECK_DIGITS
    return advance(
      "Pick confirmed. Next, go to ${next.locatorSpoken}. " +
        "Read the 3 check digits on the location label."
    )
  }

  @Synchronized
  fun isComplete(): Boolean = phase == Phase.COMPLETE

  @Synchronized
  fun isAwaitingStartOrder(): Boolean = phase == Phase.NOT_STARTED

  @Synchronized
  fun isAwaitingCheckDigits(): Boolean = phase == Phase.AWAITING_CHECK_DIGITS

  @Synchronized
  fun isAwaitingPickConfirmation(): Boolean = phase == Phase.AWAITING_PICK_CONFIRM

  @Synchronized
  fun getLastToolTrace(): VoicePickingToolTrace? = lastToolTrace

  @Synchronized
  fun getModelCheckpoint(): VoicePickingModelCheckpoint =
    VoicePickingModelCheckpoint(phase = phase.name, lastValidInstruction = lastValidInstruction)

  @Synchronized
  fun getCurrentPickItemName(): String? = currentPick?.itemName

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
      val cancelledItemName = cancelledPick.itemName.replace("USB C", "USB-C")
      val message =
        "Warehouse update. You no longer need $cancelledItemName. " +
          "Order ${spellDigits(curOrder.orderNumber)} is complete. Deliver to packing station " +
          "${curOrder.packingStation}. Nice work."
      advance(message)
      return WarehouseCancellationResult(interruptedActivePick = true, workerMessage = message)
    }

    phase = Phase.AWAITING_CHECK_DIGITS
    val nextInstruction =
      "Go to ${next.locatorSpoken}. Read the 3 check digits on the location label."
    val cancelledItemName = cancelledPick.itemName.replace("USB C", "USB-C")
    val message =
      "Warehouse update. You no longer need $cancelledItemName. Next, $nextInstruction"
    lastValidInstruction = nextInstruction
    say(message)
    return WarehouseCancellationResult(interruptedActivePick = true, workerMessage = message)
  }

  @Synchronized
  @Tool(
    description =
      "Repeat the current instruction. Call when the worker says 'repeat' or 'say again', or when " +
        "their utterance doesn't match another fast picking tool. After the tool returns, reply " +
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
      "Say start order, followed by the order number, to begin picking. For example: start order 4 2."
  }
}
