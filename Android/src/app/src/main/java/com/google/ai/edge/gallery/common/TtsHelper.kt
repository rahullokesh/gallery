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

package com.google.ai.edge.gallery.common

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AGTtsHelper"

/** Characters that end a sentence when followed by whitespace. */
private val SENTENCE_TERMINATORS = charArrayOf('.', '!', '?', '…')

/** Characters that end a sentence on their own (newlines and full-width CJK punctuation). */
private val STANDALONE_TERMINATORS = charArrayOf('\n', '。', '！', '？', '．')

/**
 * Force a flush once this much text accumulates without a sentence boundary, so unpunctuated or
 * unspaced (e.g. CJK) replies still stream instead of waiting for the response to finish.
 */
private const val FORCE_FLUSH_MIN_LENGTH = 200

/**
 * Speaks streaming LLM responses aloud using the device's system text-to-speech engine.
 *
 * Streamed partial results are buffered until a complete sentence is available, then queued to the
 * engine, so playback starts while the model is still generating the rest of the response.
 *
 * [stop] mutes the helper so that inference callbacks still in flight can't restart speech; the
 * next [begin] un-mutes it for the following reply.
 */
@Singleton
class TtsHelper @Inject constructor(@ApplicationContext private val context: Context) {
  private enum class InitState {
    PENDING,
    READY,
    FAILED,
  }

  private val lock = Any()
  private var initState = InitState.PENDING
  private var muted = false
  private val pendingText = StringBuilder()

  // Sentences produced before the engine finished initializing, spoken once it is ready.
  private val earlySentences = mutableListOf<String>()
  private var utteranceCounter = 0

  // Engines to try in order; null means the system default. Some OEM frameworks (e.g. Samsung's)
  // reject default-engine resolution for sideloaded apps, while an explicitly named engine binds
  // fine.
  private val candidateEngines = listOf<String?>(null, "com.samsung.SMT", "com.google.android.tts")
  private var engineIndex = 0
  private var tts: TextToSpeech? = null

  init {
    tts = createEngine()
  }

  private fun createEngine(): TextToSpeech {
    val engineName = candidateEngines[engineIndex]
    Log.d(TAG, "Initializing text-to-speech engine: ${engineName ?: "<system default>"}")
    return if (engineName == null) {
      TextToSpeech(context) { status -> handleInitResult(status) }
    } else {
      TextToSpeech(context, { status -> handleInitResult(status) }, engineName)
    }
  }

  private fun handleInitResult(status: Int) {
    synchronized(lock) {
      if (status == TextToSpeech.SUCCESS) {
        initState = InitState.READY
        Log.d(TAG, "Text-to-speech engine ready: ${candidateEngines[engineIndex] ?: "<default>"}")
        for (sentence in earlySentences) {
          speakOut(sentence)
        }
        earlySentences.clear()
        return
      }
      if (engineIndex < candidateEngines.size - 1) {
        engineIndex++
        tts?.shutdown()
        tts = createEngine()
      } else {
        initState = InitState.FAILED
        Log.e(TAG, "Failed to initialize any text-to-speech engine (status=$status)")
        earlySentences.clear()
      }
    }
  }

  /** Starts a new spoken reply: cuts off earlier speech and clears leftover buffered text. */
  fun begin() {
    synchronized(lock) {
      muted = false
      pendingText.setLength(0)
      earlySentences.clear()
    }
    tts?.stop()
  }

  /** Appends a streamed partial result and speaks any complete sentences accumulated so far. */
  fun feed(delta: String) {
    synchronized(lock) {
      if (muted) {
        return
      }
      pendingText.append(delta)

      // Flush up to the last sentence boundary. An ASCII terminator must be followed by
      // whitespace: one at the very end of the buffer may be mid-token (e.g. "3.14" split across
      // chunks), so it is left for a later flush.
      var flushEnd = -1
      for (i in 0 until pendingText.length) {
        val c = pendingText[i]
        if (
          STANDALONE_TERMINATORS.contains(c) ||
            (SENTENCE_TERMINATORS.contains(c) &&
              i < pendingText.length - 1 &&
              pendingText[i + 1].isWhitespace())
        ) {
          flushEnd = i
        }
      }
      if (flushEnd < 0 && pendingText.length >= FORCE_FLUSH_MIN_LENGTH) {
        val lastWhitespace = pendingText.indexOfLast { it.isWhitespace() }
        flushEnd = if (lastWhitespace >= 0) lastWhitespace else pendingText.length - 1
      }
      if (flushEnd >= 0) {
        val sentence = pendingText.substring(0, flushEnd + 1)
        pendingText.delete(0, flushEnd + 1)
        enqueue(sentence)
      }
    }
  }

  /** Speaks whatever is left in the buffer. Call when response generation is done. */
  fun finish() {
    synchronized(lock) {
      if (muted) {
        return
      }
      val rest = pendingText.toString()
      pendingText.setLength(0)
      enqueue(rest)
    }
  }

  /**
   * Stops ongoing and queued speech, clears buffered text, and mutes the helper until the next
   * [begin] so late-arriving stream callbacks can't restart speech.
   */
  fun stop() {
    synchronized(lock) {
      muted = true
      pendingText.setLength(0)
      earlySentences.clear()
    }
    tts?.stop()
  }

  private fun enqueue(text: String) {
    val speakable = toSpeakableText(text)
    if (speakable.isEmpty()) {
      return
    }
    when (initState) {
      InitState.PENDING -> earlySentences.add(speakable)
      InitState.READY -> speakOut(speakable)
      InitState.FAILED -> {} // Engine unavailable; drop the text.
    }
  }

  private fun speakOut(text: String) {
    if (initState != InitState.READY) {
      return
    }
    // The engine rejects utterances longer than getMaxSpeechInputLength() outright, so split
    // oversized text into sequentially queued chunks.
    val maxLength = (TextToSpeech.getMaxSpeechInputLength() - 1).coerceAtLeast(1)
    var start = 0
    while (start < text.length) {
      val end = minOf(start + maxLength, text.length)
      tts?.speak(
        text.substring(start, end),
        TextToSpeech.QUEUE_ADD,
        null,
        "ag-tts-${utteranceCounter++}",
      )
      start = end
    }
  }

  /** Strips common markdown syntax so it is not read out loud. */
  private fun toSpeakableText(text: String): String {
    return text
      // Links: keep the link text, drop the URL.
      .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
      .replace(Regex("[*_`#>~]"), "")
      .trim()
  }
}
