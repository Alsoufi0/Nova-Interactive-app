package com.codex.novamessenger

import android.app.Activity
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale

class VoiceMessageManager(private val activity: Activity) : TextToSpeech.OnInitListener {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var player: MediaPlayer? = null
    private val tts = TextToSpeech(activity, this)
    private var ttsReady = false

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
        callback("")
    }

    fun playAudio(path: String) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(path)
            setOnCompletionListener { it.release() }
            prepare()
            start()
        }
    }

    fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova-message")
    }

    fun shutdown() {
        runCatching { recorder?.release() }
        runCatching { player?.release() }
        tts.shutdown()
    }

    private fun String.cleanFilePart(): String = replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(activity) else MediaRecorder()
}
