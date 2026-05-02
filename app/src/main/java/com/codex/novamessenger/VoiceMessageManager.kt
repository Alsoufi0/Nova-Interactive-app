package com.codex.novamessenger

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.Locale

class VoiceMessageManager(private val activity: Activity) : TextToSpeech.OnInitListener {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var player: MediaPlayer? = null
    private val tts = TextToSpeech(activity, this)
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.language = Locale.getDefault()
    }

    fun startRecording(destination: String): File {
        val dir = File(activity.getExternalFilesDir(null), "messages").apply { mkdirs() }
        val targetFile = File(dir, "message_${destination.cleanFilePart()}_${System.currentTimeMillis()}.m4a")
        outputFile = targetFile
        recorder = newRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setOutputFile(targetFile.absolutePath)
            prepare()
            start()
        }
        return targetFile
    }

    fun stopRecording(): File? {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        return outputFile
    }

    fun transcribeOnce(prompt: String, callback: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            callback("")
            return
        }
        mainHandler.post {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            speechRecognizer?.destroy()
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull().orEmpty()
                    callback(text)
                    recognizer.destroy()
                    if (speechRecognizer === recognizer) speechRecognizer = null
                }
                override fun onError(error: Int) {
                    Log.w(TAG, "transcribeOnce error code: $error")
                    callback("")
                    recognizer.destroy()
                    if (speechRecognizer === recognizer) speechRecognizer = null
                }
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer.startListening(intent)
        }
    }

    fun playAudio(path: String) {
        player?.release()
        player = null
        val mp = MediaPlayer()
        try {
            mp.setDataSource(path)
            mp.setOnCompletionListener { it.release() }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            mp.release()
            throw e
        }
    }

    fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova-message")
    }

    fun shutdown() {
        runCatching { recorder?.release() }
        runCatching { player?.release() }
        mainHandler.post {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
        tts.shutdown()
    }

    private companion object {
        private const val TAG = "NovaVoiceManager"
    }

    private fun String.cleanFilePart(): String = replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(activity) else MediaRecorder()
}
