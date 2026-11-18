package com.example.domain.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TtsState(
  val isReady: Boolean = false,
  val isPlaying: Boolean = false,
  val currentBlockIndex: Int = 0,
  val speechRate: Float = 1.0f,
  val errorMessage: String? = null
)

class TtsReaderController(
  context: Context,
  private val onChapterFinished: () -> Unit
) {
  private val _state = MutableStateFlow(TtsState())
  val state: StateFlow<TtsState> = _state.asStateFlow()

  private var tts: TextToSpeech? = null
  private var paragraphs: List<String> = emptyList()
  private var currentIndex: Int = 0

  init {
    tts = TextToSpeech(context.applicationContext) { status ->
      if (status == TextToSpeech.SUCCESS) {
        val esLocale = Locale.forLanguageTag("es-ES")
        val langResult = tts?.setLanguage(esLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
          tts?.language = Locale.getDefault()
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) = Unit
          override fun onDone(utteranceId: String?) {
            if (_state.value.isPlaying) {
              val next = currentIndex + 1
              if (next < paragraphs.size) {
                currentIndex = next
                speakCurrent()
              } else {
                _state.value = _state.value.copy(isPlaying = false, currentBlockIndex = 0)
                onChapterFinished()
              }
            }
          }

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) {
            _state.value = _state.value.copy(isPlaying = false, errorMessage = "Error al reproducir voz.")
          }
        })
        _state.value = _state.value.copy(isReady = true)
      } else {
        _state.value = _state.value.copy(
          isReady = false,
          errorMessage = "Motor de voz no disponible en este dispositivo."
        )
      }
    }
  }

  fun loadParagraphs(items: List<String>, startIndex: Int = 0) {
    paragraphs = items.filter { it.isNotBlank() }
    currentIndex = startIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
    _state.value = _state.value.copy(currentBlockIndex = currentIndex)
  }

  fun play(startAt: Int? = null) {
    if (!_state.value.isReady || paragraphs.isEmpty()) return
    if (startAt != null) {
      currentIndex = startAt.coerceIn(0, paragraphs.size - 1)
    }
    _state.value = _state.value.copy(isPlaying = true, errorMessage = null)
    speakCurrent()
  }

  fun pause() {
    _state.value = _state.value.copy(isPlaying = false)
    tts?.stop()
  }

  fun nextParagraph() {
    if (paragraphs.isEmpty()) return
    currentIndex = (currentIndex + 1).coerceAtMost(paragraphs.size - 1)
    _state.value = _state.value.copy(currentBlockIndex = currentIndex)
    if (_state.value.isPlaying) speakCurrent()
  }

  fun previousParagraph() {
    if (paragraphs.isEmpty()) return
    currentIndex = (currentIndex - 1).coerceAtLeast(0)
    _state.value = _state.value.copy(currentBlockIndex = currentIndex)
    if (_state.value.isPlaying) speakCurrent()
  }

  fun setSpeechRate(rate: Float) {
    val safeRate = rate.coerceIn(0.6f, 2.0f)
    _state.value = _state.value.copy(speechRate = safeRate)
    tts?.setSpeechRate(safeRate)
    if (_state.value.isPlaying) {
      speakCurrent()
    }
  }

  private fun speakCurrent() {
    val text = paragraphs.getOrNull(currentIndex) ?: return
    _state.value = _state.value.copy(currentBlockIndex = currentIndex)
    tts?.setSpeechRate(_state.value.speechRate)
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lettor_tts_$currentIndex")
  }

  fun shutdown() {
    runCatching {
      tts?.stop()
      tts?.shutdown()
    }
    tts = null
  }
}
