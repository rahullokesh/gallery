package com.google.ai.edge.gallery.customtasks.agentchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePickingToolsTest {

  @Test
  fun wrongCheckDigits_stayAtTheSameLocationUntilTheCorrectDigitsArrive() {
    val tools = VoicePickingTools()

    assertContains(tools.startOrder("42"), "aisle 12, bay 3, shelf 2")
    assertContains(tools.confirmArrival(), "Read the 3 check digits")

    assertContains(tools.verifyCheckDigits("123"), "Wrong check digits")
    assertTrue(tools.isAwaitingCheckDigits())
    assertTrace(tools, interpreted = "123", expected = "472", accepted = false)

    assertContains(tools.verifyCheckDigits("472"), "Location confirmed")
    assertFalse(tools.isAwaitingCheckDigits())
    assertTrace(tools, interpreted = "472", expected = "472", accepted = true)
  }

  @Test
  fun secondPick_wrongDigitsDoNotReuseTheFirstLocationsState() {
    val tools = VoicePickingTools()
    startAndConfirmFirstPick(tools)

    assertContains(tools.confirmArrival(), "Aisle 7, bay 1, shelf 4")
    assertContains(tools.verifyCheckDigits("234"), "Wrong check digits")
    assertTrue(tools.isAwaitingCheckDigits())
    assertTrace(tools, interpreted = "234", expected = "815", accepted = false)

    assertContains(tools.verifyCheckDigits("815"), "Location confirmed")
    assertFalse(tools.isAwaitingCheckDigits())
    assertTrace(tools, interpreted = "815", expected = "815", accepted = true)
  }

  @Test
  fun wrongItemOrQuantity_staysOnThePickConfirmationStep() {
    val tools = VoicePickingTools()
    tools.startOrder("42")
    tools.confirmArrival()
    tools.verifyCheckDigits("472")
    tools.confirmItemLocated()

    assertContains(tools.confirmPick("950", 3), "Wrong item")
    assertTrue(tools.isAwaitingPickConfirmation())
    assertTrace(tools, interpreted = "950, quantity 3", expected = "951, quantity 3", accepted = false)

    assertContains(tools.confirmPick("951", 2), "Quantity should be 3")
    assertTrue(tools.isAwaitingPickConfirmation())
    assertTrace(tools, interpreted = "951, quantity 2", expected = "951, quantity 3", accepted = false)

    assertContains(tools.confirmPick("951", 3), "Next, go to aisle 7")
    assertFalse(tools.isAwaitingPickConfirmation())
    assertTrace(tools, interpreted = "951, quantity 3", expected = "951, quantity 3", accepted = true)
  }

  @Test
  fun outOfOrderCalls_doNotAdvanceTheWorkflow() {
    val tools = VoicePickingTools()

    assertContains(tools.verifyCheckDigits("472"), "Say start order")
    assertFalse(tools.isAwaitingCheckDigits())
    assertFalse(tools.isComplete())

    tools.startOrder("42")
    assertContains(tools.confirmPick("951", 3), "Order 4 2 started")
    assertFalse(tools.isAwaitingPickConfirmation())
    assertFalse(tools.isComplete())
  }

  @Test
  fun unknownOrder_keepsTheInitialCheckpointForTheRetry() {
    val tools = VoicePickingTools()

    assertContains(tools.startOrder("24"), "Order 2 4 not found")
    assertTrue(tools.isAwaitingStartOrder())
    assertEquals("NOT_STARTED", tools.getModelCheckpoint().phase)
    assertTrue(tools.getModelCheckpoint().lastValidInstruction.contains("Say start order"))
  }

  @Test
  fun order42_completesOnlyAfterAllThreePicksAreConfirmed() {
    val tools = VoicePickingTools()

    tools.startOrder("42")
    tools.confirmArrival()
    tools.verifyCheckDigits("472")
    tools.confirmItemLocated()
    assertContains(tools.confirmPick("951", 3), "aisle 7")
    assertFalse(tools.isComplete())

    tools.confirmArrival()
    tools.verifyCheckDigits("815")
    tools.confirmItemLocated()
    assertContains(tools.confirmPick("208", 1), "aisle 3")
    assertFalse(tools.isComplete())

    tools.confirmArrival()
    tools.verifyCheckDigits("339")
    tools.confirmItemLocated()
    assertContains(tools.confirmPick("664", 5), "Order 4 2 complete")
    assertTrue(tools.isComplete())
  }

  @Test
  fun cancellingTheActivePick_interruptsAndMovesToTheNextLocation() {
    val tools = VoicePickingTools()
    tools.startOrder("42")
    tools.confirmArrival()

    val result = tools.cancelWarehouseItem("951")

    assertTrue(result.interruptedActivePick)
    assertContains(requireNotNull(result.workerMessage), "You no longer need USB C cables")
    assertContains(requireNotNull(result.workerMessage), "aisle 7, bay 1, shelf 4")
    assertEquals("AWAITING_ARRIVAL", tools.getModelCheckpoint().phase)
    assertContains(tools.getModelCheckpoint().lastValidInstruction, "Go to aisle 7, bay 1, shelf 4")
    assertFalse(tools.getModelCheckpoint().lastValidInstruction.contains("Warehouse update"))

    assertContains(tools.confirmArrival(), "Aisle 7, bay 1, shelf 4")
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
    assertContains(tools.confirmPick("951", 3), "aisle 3, bay 6, shelf 1")
    assertContains(tools.confirmArrival(), "Aisle 3, bay 6, shelf 1")
  }

  @Test
  fun nextPick_usesTheLatestWarehouseAvailabilitySnapshot() {
    val tools = VoicePickingTools()

    tools.syncCancelledWarehouseItems(setOf("951", "208"))

    assertContains(tools.startOrder("42"), "aisle 3, bay 6, shelf 1")
    assertContains(tools.confirmArrival(), "Aisle 3, bay 6, shelf 1")
  }

  @Test
  fun cancellingTwoFuturePicks_thenTheActivePick_skipsStraightToTheRemainingPick() {
    val tools = VoicePickingTools()
    tools.startOrder("42")

    tools.cancelWarehouseItem("208")
    tools.cancelWarehouseItem("664")
    val result = tools.cancelWarehouseItem("951")

    assertTrue(result.interruptedActivePick)
    assertContains(requireNotNull(result.workerMessage), "Order 4 2 is complete")
    assertContains(requireNotNull(result.workerMessage), "Deliver to packing station 4. Nice work.")
    assertTrue(tools.isComplete())
  }

  private fun startAndConfirmFirstPick(tools: VoicePickingTools) {
    tools.startOrder("42")
    tools.confirmArrival()
    tools.verifyCheckDigits("472")
    tools.confirmItemLocated()
    tools.confirmPick("951", 3)
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
