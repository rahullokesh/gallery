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

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

private const val TAG = "AGVoicePickingTools"

/** One pick line of a mock warehouse order. */
private data class Pick(
  /** Spoken location instruction, e.g. "aisle 12, bay 3, shelf 2". */
  val locatorSpoken: String,
  /** The 3 check digits printed on the location label. */
  val checkDigits: String,
  /** Item description spoken to the worker. */
  val itemName: String,
  /** Last 3 digits of the item's product code, used to verify the right product. */
  val itemLast3: String,
  /** How many units to pick. */
  val quantity: Int,
)

private data class MockOrder(val orderNumber: String, val packingStation: String, val picks: List<Pick>)

private val MOCK_ORDERS =
  listOf(
    MockOrder(
      orderNumber = "42",
      packingStation = "4",
      picks =
        listOf(
          Pick("aisle 12, bay 3, shelf 2", "472", "USB C cables", "951", 3),
          Pick("aisle 7, bay 1, shelf 4", "815", "wireless headphones", "208", 1),
          Pick("aisle 3, bay 6, shelf 1", "339", "phone cases", "664", 5),
        ),
    ),
    MockOrder(
      orderNumber = "7",
      packingStation = "2",
      picks = listOf(Pick("aisle 5, bay 2, shelf 3", "184", "power banks", "330", 2)),
    ),
  )

/** Reads digits with spaces so the TTS engine speaks them one by one ("472" -> "4 7 2"). */
private fun spellDigits(digits: String): String = digits.toCharArray().joinToString(" ")

private fun String.digitsOnly(): String = filter { it.isDigit() }

/**
 * Tools backing the voice-picking demo skill.
 *
 * All order data, workflow state, and every sentence spoken to the worker live HERE, in
 * deterministic Kotlin. The model's only job is to route the worker's spoken utterance to the
 * right tool and relay the returned [KEY_SAY] text verbatim.
 */
class VoicePickingTools : ToolSet {

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

  private val currentPick: Pick?
    get() = order?.picks?.getOrNull(pickIndex)

  /** Clears the picking session. Call whenever the chat session is reset or re-initialized. */
  @Synchronized
  fun reset() {
    order = null
    pickIndex = 0
    phase = Phase.NOT_STARTED
    lastSayText = SIGN_ON_PROMPT
  }

  @Synchronized
  @Tool(
    description =
      "Start a warehouse picking session. Call when the worker says 'start order' followed by an " +
        "order number. After the tool returns, reply with exactly its sayText, verbatim."
  )
  fun startOrder(
    @ToolParam(description = "The spoken order number, digits only.") orderNumber: String
  ): Map<String, Any> {
    val normalized = orderNumber.digitsOnly()
    Log.d(TAG, "startOrder($orderNumber)")
    val matched = MOCK_ORDERS.find { it.orderNumber == normalized }
    if (matched == null) {
      return say("Order ${spellDigits(normalized.ifEmpty { orderNumber })} not found. $SIGN_ON_PROMPT")
    }
    order = matched
    pickIndex = 0
    phase = Phase.AWAITING_CHECK_DIGITS
    val pick = matched.picks[0]
    return say(
      "Order ${spellDigits(matched.orderNumber)} started, ${matched.picks.size} picks. " +
        "Go to ${pick.locatorSpoken}, and say the 3 check digits on the location label."
    )
  }

  @Synchronized
  @Tool(
    description =
      "Verify the worker is at the right location. Call when the worker says 3 digits on their " +
        "own, e.g. '4 7 2'. After the tool returns, reply with exactly its sayText, verbatim."
  )
  fun verifyCheckDigits(
    @ToolParam(description = "The 3 spoken check digits, digits only.") checkDigits: String
  ): Map<String, Any> {
    Log.d(TAG, "verifyCheckDigits($checkDigits) phase=$phase")
    val pick = currentPick ?: return notInSession()
    if (phase != Phase.AWAITING_CHECK_DIGITS) {
      return say(lastSayText)
    }
    if (checkDigits.digitsOnly() != pick.checkDigits) {
      return say(
        "Wrong check digits. You should be at ${pick.locatorSpoken}. " +
          "Read the 3 digits printed on the location label."
      )
    }
    phase = Phase.AWAITING_PICK_CONFIRM
    return say(
      "Location confirmed. Pick ${pick.quantity} ${pick.itemName}, item ending " +
        "${spellDigits(pick.itemLast3)}. Say the item digits and the quantity you picked."
    )
  }

  @Synchronized
  @Tool(
    description =
      "Confirm a completed pick. Call when the worker says item digits plus a count, e.g. " +
        "'9 5 1, picked 3'. After the tool returns, reply with exactly its sayText, verbatim."
  )
  fun confirmPick(
    @ToolParam(description = "The last 3 digits of the picked item, digits only.")
    itemDigits: String,
    @ToolParam(description = "How many units the worker picked.") quantity: Int,
  ): Map<String, Any> {
    Log.d(TAG, "confirmPick($itemDigits, $quantity) phase=$phase")
    val curOrder = order ?: return notInSession()
    val pick = currentPick ?: return notInSession()
    if (phase != Phase.AWAITING_PICK_CONFIRM) {
      return say(lastSayText)
    }
    if (itemDigits.digitsOnly() != pick.itemLast3) {
      return say(
        "Wrong item. You need the item ending ${spellDigits(pick.itemLast3)}. Check the label and " +
          "try again."
      )
    }
    if (quantity != pick.quantity) {
      return say(
        "Quantity should be ${pick.quantity}, you said $quantity. Put the extra back or pick the " +
          "rest, then say the item digits and quantity again."
      )
    }
    pickIndex++
    val next = currentPick
    if (next == null) {
      phase = Phase.COMPLETE
      return say(
        "Pick confirmed. Order ${spellDigits(curOrder.orderNumber)} complete. Deliver to packing " +
          "station ${curOrder.packingStation}. Nice work."
      )
    }
    phase = Phase.AWAITING_CHECK_DIGITS
    return say(
      "Pick confirmed. Next, go to ${next.locatorSpoken}, and say the 3 check digits on the " +
        "location label."
    )
  }

  @Synchronized
  @Tool(
    description =
      "Repeat the current instruction. Call when the worker says 'repeat' or 'say again', or when " +
        "their utterance doesn't match any other picking tool. After the tool returns, reply with exactly its sayText, verbatim."
  )
  fun repeatInstruction(): Map<String, Any> {
    Log.d(TAG, "repeatInstruction phase=$phase")
    return say(lastSayText)
  }

  private fun say(text: String): Map<String, Any> {
    lastSayText = text
    return mapOf(
      KEY_SAY to text,
      "status" to "succeeded",
      "instruction" to
        "Your entire reply must be EXACTLY the sayText value above, word for word. Do not " +
          "paraphrase, narrate, or add anything.",
    )
  }

  private fun notInSession(): Map<String, Any> = say(SIGN_ON_PROMPT)

  companion object {
    private const val KEY_SAY = "sayText"
    private const val SIGN_ON_PROMPT =
      "Say start order, followed by the order number, to begin picking. For example: start order 4 2."
  }
}
