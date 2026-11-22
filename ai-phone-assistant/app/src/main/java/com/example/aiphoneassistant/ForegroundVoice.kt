package com.example.aiphoneassistant

import android.content.Context
import android.content.Intent
import android.os.*
import android.speech.*

class ForegroundVoice(private val context: Context, private val command: (String) -> Unit, private val status: (String) -> Unit) {
 private val handler = Handler(Looper.getMainLooper())
 private var recognizer: SpeechRecognizer? = null
 private var waitingUntil = 0L
 private val prefix = Regex("""^(?:дип\s*сик|deep\s*seek)\b[\s,.!?—-]*""", RegexOption.IGNORE_CASE)

 fun stop() {
  handler.removeCallbacksAndMessages(null)
  val old = recognizer
  recognizer = null
  old?.destroy()
  waitingUntil = 0L
 }

 fun start() {
  if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
   status("На устройстве недоступно локальное распознавание. Используйте кнопку микрофона.")
   return
  }
  val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
  recognizer = engine
  val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
   putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
   putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
  }
  fun listen(delay: Long) {
   handler.postDelayed({ if (recognizer === engine) engine.startListening(intent) }, delay)
  }
  engine.setRecognitionListener(object : RecognitionListener {
   override fun onResults(data: Bundle?) {
    if (recognizer !== engine) return
    val phrase = data?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
    val wake = prefix.find(phrase)
    val now = SystemClock.elapsedRealtime()
    if (wake != null || now < waitingUntil) {
     val text = phrase.substring(wake?.range?.last?.plus(1) ?: 0).trim()
     if (text.isEmpty()) { waitingUntil = now + 8000; status("Скажите команду") }
     else { waitingUntil = 0; command(text) }
    }
    listen(300)
   }
   override fun onError(error: Int) {
    if (recognizer !== engine) return
    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) listen(400)
    else { stop(); status("Распознавание остановлено (код $error). Проверьте русский языковой пакет и включите режим снова.") }
   }
   override fun onReadyForSpeech(params: Bundle?) {}
   override fun onBeginningOfSpeech() {}
   override fun onRmsChanged(value: Float) {}
   override fun onBufferReceived(buffer: ByteArray?) {}
   override fun onEndOfSpeech() {}
   override fun onPartialResults(results: Bundle?) {}
   override fun onEvent(type: Int, params: Bundle?) {}
  })
  listen(200)
 }
}
