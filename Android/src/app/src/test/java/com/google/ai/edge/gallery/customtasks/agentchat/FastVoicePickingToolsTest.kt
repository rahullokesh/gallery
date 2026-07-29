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

    assertContains(result, "Job 4 2 started, 3 moves")
    assertContains(result, "Proceed to bay 12, rack position 3, level 2")
    assertContains(result, "read the 3 check digits")
    assertEquals("AWAITING_CHECK_DIGITS", tools.getModelCheckpoint().phase)
    assertTrue(tools.isAwaitingCheckDigits())
  }

  @Test
  fun validCheckDigits_requestTagDigitsWhenTheLoadIsSecure() {
    val tools = FastVoicePickingTools()
    tools.startOrder("42")

    val result = tools.verifyCheckDigits("472")

    assertContains(result, "Pickup location confirmed")
    assertContains(result, "power generator load, tag ending 9 5 1")
    assertContains(result, "When the load is secure, read the last 3 digits")
    assertFalse((result["sayText"] as String).contains("quantity", ignoreCase = true))
    assertEquals("AWAITING_TAG_CONFIRMATION", tools.getModelCheckpoint().phase)
    assertTrue(tools.isAwaitingTagConfirmation())
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
    val tagCheckpoint = tools.getModelCheckpoint().lastValidInstruction
    assertFalse(tagCheckpoint.contains("951"))

    assertContains(tools.confirmPick("950"), "Wrong load tag")
    assertEquals(tagCheckpoint, tools.getModelCheckpoint().lastValidInstruction)
    assertTrue(tools.isAwaitingTagConfirmation())
  }

  @Test
  fun correctTagDigits_immediatelyRequestTheNextLocationsCheckDigits() {
    val tools = FastVoicePickingTools()
    tools.startOrder("42")
    tools.verifyCheckDigits("472")

    val result = tools.confirmPick("951")

    assertContains(result, "Next, proceed to bay 7, rack position 1, level 4")
    assertContains(result, "read the 3 check digits")
    assertEquals("AWAITING_CHECK_DIGITS", tools.getModelCheckpoint().phase)
    assertFalse(tools.isComplete())
  }

  @Test
  fun order42_completesThroughLocationAndTagDigits() {
    val tools = FastVoicePickingTools()

    tools.startOrder("42")
    tools.verifyCheckDigits("472")
    tools.confirmPick("951")

    tools.verifyCheckDigits("815")
    tools.confirmPick("208")

    tools.verifyCheckDigits("339")
    val result = tools.confirmPick("664")

    assertContains(result, "Job 4 2 complete")
    assertTrue(tools.isComplete())
  }

  @Test
  fun cancellingActivePick_movesDirectlyToTheNextCheckDigitRequest() {
    val tools = FastVoicePickingTools()
    tools.startOrder("42")

    val result = tools.cancelWarehouseItem("951")

    assertTrue(result.interruptedActivePick)
    assertContains(requireNotNull(result.workerMessage), "bay 7, rack position 1, level 4")
    assertContains(requireNotNull(result.workerMessage), "read the 3 check digits")
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
    assertContains(tools.getLastSayText(), "Job 4 2 started")
    assertContains(requireNotNull(spokenResult), "Job 4 2 started")

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
