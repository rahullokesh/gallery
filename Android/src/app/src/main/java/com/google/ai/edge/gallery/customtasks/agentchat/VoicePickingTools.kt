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

/** One pickup move in a mock forklift job. */
internal data class Pick(
  /** Spoken location instruction, e.g. "bay 12, rack position 3, level 2". */
  val locatorSpoken: String,
  /** The 3 check digits printed on the location label. */
  val checkDigits: String,
  /** Equipment description spoken to the operator. */
  val itemName: String,
  /** Last 3 digits of the load tag, read aloud by the operator. */
  val itemLast3: String,
)

internal data class MockOrder(val orderNumber: String, val picks: List<Pick>)

internal val MOCK_ORDERS =
  listOf(
    MockOrder(
      orderNumber = "42",
      picks =
        listOf(
          Pick("bay 12, rack position 3, level 2", "472", "power generator", "951"),
          Pick("bay 7, rack position 1, level 4", "815", "air compressor", "208"),
          Pick("bay 3, rack position 6, level 1", "339", "water pump", "664"),
        ),
    ),
    MockOrder(
      orderNumber = "7",
      picks = listOf(Pick("bay 5, rack position 2, level 3", "184", "welding machine", "330")),
    ),
  )

/** Reads digits with spaces so the TTS engine speaks them one by one ("472" -> "4 7 2"). */
internal fun spellDigits(digits: String): String = digits.toCharArray().joinToString(" ")

internal fun String.digitsOnly(): String = filter { it.isDigit() }

/** The most recent tool input and deterministic validation result, exposed in the debug UI. */
data class VoicePickingToolTrace(
  val tool: String,
  val interpreted: String,
  val expected: String? = null,
  val accepted: Boolean? = null,
)

/** The compact, failure-free context supplied to Gemma for the next operator response. */
data class VoicePickingModelCheckpoint(
  val phase: String,
  val lastValidInstruction: String,
)

/** Result of a warehouse cancellation requested outside the voice model. */
data class WarehouseCancellationResult(
  val interruptedActivePick: Boolean,
  val workerMessage: String? = null,
)

/**
 * Tools backing the forklift pickup demo.
 *
 * All job data, workflow state, and every sentence spoken to the operator live HERE, in
 * deterministic Kotlin. The model's only job is to route the operator's spoken utterance to the
 * right tool and relay the returned [KEY_SAY] text verbatim.
 */
class VoicePickingTools : ToolSet {

  private enum class Phase {
    NOT_STARTED,
    AWAITING_ARRIVAL,
    AWAITING_CHECK_DIGITS,
    AWAITING_LOAD_SECURED,
    AWAITING_TAG_CONFIRMATION,
    COMPLETE,
  }

  private var order: MockOrder? = null
  private var pickIndex = 0
  private var phase = Phase.NOT_STARTED
  private var lastSayText = SIGN_ON_PROMPT
  private var lastValidInstruction = SIGN_ON_PROMPT
  private var lastToolTrace: VoicePickingToolTrace? = null
  private val cancelledItemLast3 = mutableSetOf<String>()

  private val currentPick: Pick?
    get() = order?.picks?.getOrNull(pickIndex)

  /** Clears the forklift job. Call whenever the chat session is reset or re-initialized. */
  @Synchronized
  fun reset() {
    order = null
    pickIndex = 0
    phase = Phase.NOT_STARTED
    lastSayText = SIGN_ON_PROMPT
    lastValidInstruction = SIGN_ON_PROMPT
    lastToolTrace = null
    cancelledItemLast3.clear()
  }

  /**
   * Starts the pickup job matching the spoken [orderNumber] (non-digits stripped).
   *
   * Unknown job numbers are rejected without changing state. On success the job's first pending
   * pick becomes active and the phase moves to [Phase.AWAITING_ARRIVAL], or straight to
   * [Phase.COMPLETE] when every move was already cancelled.
   */
  @Synchronized
  @Tool(
    description =
      "Start a forklift pickup job. Call when the operator says 'start job' followed by a job " +
        "number. After the tool returns, reply with exactly its sayText, verbatim."
  )
  fun startOrder(
    @ToolParam(description = "The spoken job number, digits only.") orderNumber: String
  ): Map<String, Any> {
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
    phase = Phase.AWAITING_ARRIVAL
    return advance(
      "Job ${spellDigits(matched.orderNumber)} started, ${matched.picks.size} moves. " +
        "Proceed to ${pick.locatorSpoken}. Speak when safely stopped."
    )
  }

  /**
   * Marks the operator as safely stopped at the current bay and moves [Phase.AWAITING_ARRIVAL]
   * to [Phase.AWAITING_CHECK_DIGITS], prompting for the location label's check digits.
   *
   * Takes no parameters: arriving carries no value to extract, so any utterance in this phase
   * counts. Out-of-phase calls re-speak the current prompt without advancing.
   */
  @Synchronized
  @Tool(description = "Confirm the operator is safely stopped at the current bay. Reply with sayText.")
  fun confirmArrival(): Map<String, Any> {
    val pick = currentPick ?: return notInSession()
    if (phase != Phase.AWAITING_ARRIVAL) return say(lastSayText)
    phase = Phase.AWAITING_CHECK_DIGITS
    return advance(
      "${pick.locatorSpoken.replaceFirstChar { it.uppercase() }}. Read the 3 check digits on the location label."
    )
  }

  /**
   * Compares the spoken [checkDigits] (non-digits stripped) against the active pick's check
   * digits. A match moves [Phase.AWAITING_CHECK_DIGITS] to [Phase.AWAITING_LOAD_SECURED] and
   * instructs the lift; a mismatch keeps the phase and speaks a correction. Every attempt is
   * recorded in [lastToolTrace] for the debug UI.
   */
  @Synchronized
  @Tool(
    description =
      "Verify the operator is at the right pickup location. Call when the operator says 3 digits on their " +
        "own, e.g. '4 7 2'. After the tool returns, reply with exactly its sayText, verbatim."
  )
  fun verifyCheckDigits(
    @ToolParam(description = "The 3 spoken check digits, digits only.") checkDigits: String
  ): Map<String, Any> {
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
    phase = Phase.AWAITING_LOAD_SECURED
    return advance(
      "Pickup location confirmed. Lift the ${pick.itemName} load, tag ending " +
        "${spellDigits(pick.itemLast3)}. Speak when the load is secure."
    )
  }

  /**
   * Confirms the equipment load is secured and moves [Phase.AWAITING_LOAD_SECURED] to
   * [Phase.AWAITING_TAG_CONFIRMATION]. Parameterless like [confirmArrival]; out-of-phase calls
   * re-speak the current prompt without advancing.
   */
  @Synchronized
  @Tool(description = "Confirm the requested equipment load is secure. Reply with sayText.")
  fun confirmItemLocated(): Map<String, Any> {
    currentPick ?: return notInSession()
    if (phase != Phase.AWAITING_LOAD_SECURED) return say(lastSayText)
    phase = Phase.AWAITING_TAG_CONFIRMATION
    return advance("Read the last 3 digits on the load tag to confirm.")
  }

  /**
   * Verifies the spoken last-3 load-tag digits ([itemDigits], non-digits stripped) against the
   * active pick. On a match the pick completes and the workflow returns to
   * [Phase.AWAITING_ARRIVAL] for the next pending pick, or ends at [Phase.COMPLETE] after the
   * last one; a mismatch keeps the phase and speaks a correction. Every attempt is recorded in
   * [lastToolTrace].
   */
  @Synchronized
  @Tool(
    description =
      "Verify the secured equipment load. In AWAITING_TAG_CONFIRMATION, call when the operator " +
        "reads the last 3 load-tag digits. After the tool returns, reply with exactly its sayText, " +
        "verbatim."
  )
  fun confirmPick(
    @ToolParam(description = "The last 3 spoken load-tag digits, digits only.")
    itemDigits: String,
  ): Map<String, Any> {
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
    phase = Phase.AWAITING_ARRIVAL
    return advance(
      "Pickup confirmed. Next, proceed to ${next.locatorSpoken}. Speak when safely stopped."
    )
  }

  /** True once every pick in the job is done or cancelled. */
  @Synchronized
  fun isComplete(): Boolean = phase == Phase.COMPLETE

  /** True before any job has been started. */
  @Synchronized
  fun isAwaitingStartOrder(): Boolean = phase == Phase.NOT_STARTED

  /** True when the last check-digit attempt did not advance the warehouse workflow. */
  @Synchronized
  fun isAwaitingCheckDigits(): Boolean = phase == Phase.AWAITING_CHECK_DIGITS

  /** True while Kotlin is waiting to validate the operator's three spoken load-tag digits. */
  @Synchronized
  fun isAwaitingTagConfirmation(): Boolean = phase == Phase.AWAITING_TAG_CONFIRMATION

  /** The most recent tool attempt shown in the debug UI, or null before the first call. */
  @Synchronized
  fun getLastToolTrace(): VoicePickingToolTrace? = lastToolTrace

  /** Snapshot of the phase and last valid instruction used to rebuild Gemma's context each turn. */
  @Synchronized
  fun getModelCheckpoint(): VoicePickingModelCheckpoint =
    VoicePickingModelCheckpoint(phase = phase.name, lastValidInstruction = lastValidInstruction)

  /**
   * Replaces the locally cached availability with the latest warehouse snapshot. The caller owns
   * the warehouse data; this state machine reads the snapshot whenever it chooses the next move.
   */
  @Synchronized
  fun syncCancelledWarehouseItems(itemLast3s: Set<String>) {
    cancelledItemLast3.clear()
    cancelledItemLast3.addAll(itemLast3s)
  }

  /**
   * Applies a warehouse cancellation without involving Gemma. Only cancelling the active move
   * interrupts the current voice flow; future moves are skipped when they become next.
   */
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

    phase = Phase.AWAITING_ARRIVAL
    val nextInstruction = "Proceed to ${next.locatorSpoken}. Speak when safely stopped."
    val message =
      "Warehouse update. The ${cancelledPick.itemName} load is no longer required. " +
        "Next, $nextInstruction"
    // The update is operator-facing only. Keep the saved model checkpoint at the normal next-move
    // instruction so Gemma never receives warehouse-cancellation context on a later audio turn.
    lastValidInstruction = nextInstruction
    say(message)
    return WarehouseCancellationResult(interruptedActivePick = true, workerMessage = message)
  }

  /**
   * Re-speaks [lastValidInstruction] without changing state — the safe fallback for "repeat"
   * requests and for utterances Gemma cannot route to any other tool.
   */
  @Synchronized
  @Tool(
    description =
      "Repeat the current instruction. Call when the operator says 'repeat' or 'say again', or " +
        "when their utterance doesn't match any other forklift tool. After the tool returns, " +
        "reply with exactly its sayText, verbatim."
  )
  fun repeatInstruction(): Map<String, Any> {
    lastToolTrace = VoicePickingToolTrace("repeat_instruction", "repeat", accepted = true)
    return say(lastValidInstruction)
  }

  /** Publishes [text] as the new checkpoint instruction ([lastValidInstruction]) and speaks it. */
  private fun advance(text: String): Map<String, Any> {
    lastValidInstruction = text
    return say(text)
  }

  /** Moves [pickIndex] past any picks the warehouse has cancelled since the job started. */
  private fun advanceToNextPendingPick() {
    val picks = order?.picks ?: return
    while (pickIndex < picks.size && picks[pickIndex].itemLast3 in cancelledItemLast3) {
      pickIndex++
    }
  }

  /**
   * Builds the tool result that makes the model speak [text] verbatim. Unlike [advance] this does
   * not touch the checkpoint, so guard and error replies never leak into the next model turn.
   */
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

  /** Guard reply when no job is active: re-speaks the sign-on prompt. */
  private fun notInSession(): Map<String, Any> = say(SIGN_ON_PROMPT)

  companion object {
    private const val KEY_SAY = "sayText"
    private const val SIGN_ON_PROMPT =
      "Say start job, followed by the job number, to begin forklift pickup. For example: start job 4 2."
  }
}
