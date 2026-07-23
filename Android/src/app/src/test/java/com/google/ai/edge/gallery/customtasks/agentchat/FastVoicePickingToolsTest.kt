package com.google.ai.edge.gallery.customtasks.agentchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastVoicePickingToolsTest {

  @Test
  fun startOrder_skipsArrivalAndImmediatelyRequestsCheckDigits() {
    val tools = FastVoicePickingTools()

    val result = tools.startOrder("42")

    assertContains(result, "Order 4 2 started, 3 picks")
    assertContains(result, "Go to aisle 12, bay 3, shelf 2")
    assertContains(result, "Read the 3 check digits")
    assertEquals("AWAITING_CHECK_DIGITS", tools.getModelCheckpoint().phase)
    assertTrue(tools.isAwaitingCheckDigits())
  }

  @Test
  fun validCheckDigits_skipItemLocationAndImmediatelyBeginPickConfirmation() {
    val tools = FastVoicePickingTools()
    tools.startOrder("42")

    val result = tools.verifyCheckDigits("472")

    assertContains(result, "Location confirmed")
    assertContains(result, "Pick 3 USB-C cables, item ending 9 5 1")
    assertEquals("AWAITING_PICK_CONFIRM", tools.getModelCheckpoint().phase)
    assertTrue(tools.isAwaitingPickConfirmation())
  }

  @Test
  fun wrongResponses_keepOnlyTheLastValidFastCheckpoint() {
    val tools = FastVoicePickingTools()
    tools.startOrder("42")
    val locationCheckpoint = tools.getModelCheckpoint().lastValidInstruction

    assertContains(tools.verifyCheckDigits("123"), "Wrong check digits")
    assertEquals(locationCheckpoint, tools.getModelCheckpoint().lastValidInstruction)
    assertTrue(tools.isAwaitingCheckDigits())

    tools.verifyCheckDigits("472")
    val pickCheckpoint = tools.getModelCheckpoint().lastValidInstruction

    assertContains(tools.confirmPick("950", 3), "Wrong item")
    assertEquals(pickCheckpoint, tools.getModelCheckpoint().lastValidInstruction)
    assertTrue(tools.isAwaitingPickConfirmation())

    assertContains(tools.confirmPick("951", 2), "Quantity should be 3")
    assertEquals(pickCheckpoint, tools.getModelCheckpoint().lastValidInstruction)
    assertTrue(tools.isAwaitingPickConfirmation())
  }

  @Test
  fun confirmedPick_immediatelyRequestsTheNextLocationsCheckDigits() {
    val tools = FastVoicePickingTools()
    tools.startOrder("42")
    tools.verifyCheckDigits("472")

    val result = tools.confirmPick("951", 3)

    assertContains(result, "Next, go to aisle 7, bay 1, shelf 4")
    assertContains(result, "Read the 3 check digits")
    assertEquals("AWAITING_CHECK_DIGITS", tools.getModelCheckpoint().phase)
    assertFalse(tools.isComplete())
  }

  @Test
  fun order42_completesThroughTheCondensedThreeTurnPerPickFlow() {
    val tools = FastVoicePickingTools()

    tools.startOrder("42")
    tools.verifyCheckDigits("472")
    tools.confirmPick("951", 3)

    tools.verifyCheckDigits("815")
    tools.confirmPick("208", 1)

    tools.verifyCheckDigits("339")
    val result = tools.confirmPick("664", 5)

    assertContains(result, "Order 4 2 complete")
    assertTrue(tools.isComplete())
  }

  @Test
  fun cancellingActivePick_movesDirectlyToTheNextCheckDigitRequest() {
    val tools = FastVoicePickingTools()
    tools.startOrder("42")

    val result = tools.cancelWarehouseItem("951")

    assertTrue(result.interruptedActivePick)
    assertContains(requireNotNull(result.workerMessage), "aisle 7, bay 1, shelf 4")
    assertContains(requireNotNull(result.workerMessage), "Read the 3 check digits")
    assertEquals("AWAITING_CHECK_DIGITS", tools.getModelCheckpoint().phase)
    assertFalse(tools.getModelCheckpoint().lastValidInstruction.contains("Warehouse update"))
  }

  @Test
  fun modelTurnEvidence_distinguishesToolCallsFromPlainModelOutput() {
    val tools = FastVoicePickingTools()
    var spokenResult: String? = null

    tools.beginModelTurn { spokenResult = it }
    assertFalse(tools.wasToolCalledThisTurn())

    tools.startOrder("42")
    assertTrue(tools.wasToolCalledThisTurn())
    assertContains(tools.getLastSayText(), "Order 4 2 started")
    assertContains(requireNotNull(spokenResult), "Order 4 2 started")

    tools.beginModelTurn()
    assertFalse(tools.wasToolCalledThisTurn())
  }

  @Test
  fun startOrderCalledMidOrder_doesNotResetTheFastWorkflow() {
    val tools = FastVoicePickingTools()
    tools.startOrder("42")
    val checkpoint = tools.getModelCheckpoint()

    tools.beginModelTurn()
    val result = tools.startOrder("7")

    assertContains(result, checkpoint.lastValidInstruction)
    assertEquals("AWAITING_CHECK_DIGITS", tools.getModelCheckpoint().phase)
    assertTrue(tools.isAwaitingCheckDigits())
  }

  private fun assertContains(result: Map<String, Any>, expected: String) {
    assertTrue((result["sayText"] as String).contains(expected))
  }

  private fun assertContains(value: String, expected: String) {
    assertTrue(value.contains(expected))
  }
}
