package com.google.ai.edge.gallery.customtasks.agentchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePickingToolsTest {

  @Test
  fun wrongCheckDigits_stayAtTheSameLocationUntilTheCorrectDigitsArrive() {
    val tools = VoicePickingTools()

    assertContains(tools.startOrder("42"), "bay 12, rack position 3, level 2")
    assertContains(tools.confirmArrival(), "Read the 3 check digits")

    assertContains(tools.verifyCheckDigits("123"), "Wrong check digits")
    assertTrue(tools.isAwaitingCheckDigits())
    assertTrace(tools, interpreted = "123", expected = "472", accepted = false)

    val pickupInstruction = tools.verifyCheckDigits("472")
    assertContains(pickupInstruction, "Pickup location confirmed")
    assertContains(pickupInstruction, "power generator load, tag ending 9 5 1")
    assertContains(pickupInstruction, "Speak when the load is secure")
    assertFalse((pickupInstruction["sayText"] as String).contains("quantity", ignoreCase = true))
    assertFalse(tools.isAwaitingCheckDigits())
    assertTrace(tools, interpreted = "472", expected = "472", accepted = true)
  }

  @Test
  fun secondPick_wrongDigitsDoNotReuseTheFirstLocationsState() {
    val tools = VoicePickingTools()
    startAndConfirmFirstPick(tools)

    assertContains(tools.confirmArrival(), "Bay 7, rack position 1, level 4")
    assertContains(tools.verifyCheckDigits("234"), "Wrong check digits")
    assertTrue(tools.isAwaitingCheckDigits())
    assertTrace(tools, interpreted = "234", expected = "815", accepted = false)

    assertContains(tools.verifyCheckDigits("815"), "Pickup location confirmed")
    assertFalse(tools.isAwaitingCheckDigits())
    assertTrace(tools, interpreted = "815", expected = "815", accepted = true)
  }

  @Test
  fun loadSecuredSignal_requestsTagDigitsAndCorrectTagAdvances() {
    val tools = VoicePickingTools()
    tools.startOrder("42")
    tools.confirmArrival()
    tools.verifyCheckDigits("472")

    val prompt = tools.confirmItemLocated()
    assertContains(prompt, "Read the last 3 digits on the load tag")
    assertEquals("AWAITING_TAG_CONFIRMATION", tools.getModelCheckpoint().phase)

    assertContains(tools.confirmPick("950"), "Wrong load tag")
    assertTrue(tools.isAwaitingTagConfirmation())
    assertTrace(tools, interpreted = "950", expected = "951", accepted = false)

    val result = tools.confirmPick("951")
    assertContains(result, "Pickup confirmed. Next, proceed to bay 7")
    assertEquals("AWAITING_ARRIVAL", tools.getModelCheckpoint().phase)
    assertTrace(tools, interpreted = "951", expected = "951", accepted = true)
  }

  @Test
  fun outOfOrderCalls_doNotAdvanceTheWorkflow() {
    val tools = VoicePickingTools()

    assertContains(tools.verifyCheckDigits("472"), "Say start job")
    assertFalse(tools.isAwaitingCheckDigits())
    assertFalse(tools.isComplete())

    tools.startOrder("42")
    assertContains(tools.confirmPick("951"), "Job 4 2 started")
    assertEquals("AWAITING_ARRIVAL", tools.getModelCheckpoint().phase)
    assertFalse(tools.isComplete())
  }

  @Test
  fun unknownOrder_keepsTheInitialCheckpointForTheRetry() {
    val tools = VoicePickingTools()

    assertContains(tools.startOrder("24"), "Job 2 4 not found")
    assertTrue(tools.isAwaitingStartOrder())
    assertEquals("NOT_STARTED", tools.getModelCheckpoint().phase)
    assertTrue(tools.getModelCheckpoint().lastValidInstruction.contains("Say start job"))
  }

  @Test
  fun order42_completesOnlyAfterAllThreePicksAreConfirmed() {
    val tools = VoicePickingTools()

    tools.startOrder("42")
    tools.confirmArrival()
    tools.verifyCheckDigits("472")
    tools.confirmItemLocated()
    assertContains(tools.confirmPick("951"), "bay 7")
    assertFalse(tools.isComplete())

    tools.confirmArrival()
    tools.verifyCheckDigits("815")
    tools.confirmItemLocated()
    assertContains(tools.confirmPick("208"), "bay 3")
    assertFalse(tools.isComplete())

    tools.confirmArrival()
    tools.verifyCheckDigits("339")
    tools.confirmItemLocated()
    assertContains(tools.confirmPick("664"), "Job 4 2 complete")
    assertTrue(tools.isComplete())
  }

  @Test
  fun cancellingTheActivePick_interruptsAndMovesToTheNextLocation() {
    val tools = VoicePickingTools()
    tools.startOrder("42")
    tools.confirmArrival()

    val result = tools.cancelWarehouseItem("951")

    assertTrue(result.interruptedActivePick)
    assertContains(requireNotNull(result.workerMessage), "power generator load is no longer required")
    assertContains(requireNotNull(result.workerMessage), "bay 7, rack position 1, level 4")
    assertEquals("AWAITING_ARRIVAL", tools.getModelCheckpoint().phase)
    assertContains(
      tools.getModelCheckpoint().lastValidInstruction,
      "Proceed to bay 7, rack position 1, level 4",
    )
    assertFalse(tools.getModelCheckpoint().lastValidInstruction.contains("Warehouse update"))

    assertContains(tools.confirmArrival(), "Bay 7, rack position 1, level 4")
  }

  @Test
  fun cancellingAFuturePick_marksItCancelledAndSkipsItWhenAdvancing() {
    val tools = VoicePickingTools()
    tools.startOrder("42")

    val result = tools.cancelWarehouseItem("208")

    assertFalse(result.interruptedActivePick)
    assertEquals(null, result.workerMessage)

    tools.confirmArrival()
    tools.verifyCheckDigits("472")
    tools.confirmItemLocated()
    assertContains(tools.confirmPick("951"), "bay 3, rack position 6, level 1")
    assertContains(tools.confirmArrival(), "Bay 3, rack position 6, level 1")
  }

  @Test
  fun nextPick_usesTheLatestWarehouseAvailabilitySnapshot() {
    val tools = VoicePickingTools()

    tools.syncCancelledWarehouseItems(setOf("951", "208"))

    assertContains(tools.startOrder("42"), "bay 3, rack position 6, level 1")
    assertContains(tools.confirmArrival(), "Bay 3, rack position 6, level 1")
  }

  @Test
  fun cancellingTwoFuturePicks_thenTheActivePick_skipsStraightToTheRemainingPick() {
    val tools = VoicePickingTools()
    tools.startOrder("42")

    tools.cancelWarehouseItem("208")
    tools.cancelWarehouseItem("664")
    val result = tools.cancelWarehouseItem("951")

    assertTrue(result.interruptedActivePick)
    assertContains(requireNotNull(result.workerMessage), "Job 4 2 is complete")
    assertContains(requireNotNull(result.workerMessage), "Nice work.")
    assertTrue(tools.isComplete())
  }

  private fun startAndConfirmFirstPick(tools: VoicePickingTools) {
    tools.startOrder("42")
    tools.confirmArrival()
    tools.verifyCheckDigits("472")
    tools.confirmItemLocated()
    tools.confirmPick("951")
  }

  private fun assertContains(result: Map<String, Any>, expected: String) {
    assertTrue((result["sayText"] as String).contains(expected))
  }

  private fun assertContains(value: String, expected: String) {
    assertTrue(value.contains(expected))
  }

  private fun assertTrace(
    tools: VoicePickingTools,
    interpreted: String,
    expected: String,
    accepted: Boolean,
  ) {
    val trace = requireNotNull(tools.getLastToolTrace())
    assertEquals(interpreted, trace.interpreted)
    assertEquals(expected, trace.expected)
    assertEquals(accepted, trace.accepted)
  }
}
