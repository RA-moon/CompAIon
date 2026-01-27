package com.example.offlinevoice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

class AssistantController(
  private val context: Context,
  private val onState: (String) -> Unit,
  private val onTranscript: (String) -> Unit,
  private val onAnswer: (String) -> Unit
) {
  private val main = Handler(Looper.getMainLooper())
  private val io = Executors.newSingleThreadExecutor()

  private val recorder = AudioRecorder(context)
  private val whisper = WhisperBridge()
  private val tts = TtsEngine(context)
  private val mlc = MlcEngine(context)
  private val tag = "CompAIon.Controller"

  private val wavFile: File by lazy { File(context.cacheDir, "ptt.wav") }
  private val modelFile: File by lazy {
    // Prefer internal app storage to avoid MIUI external storage restrictions.
    File(context.filesDir, "models/stt/ggml-base.bin")
  }

  fun startPtt() {
    Log.d(tag, "startPtt()")
    onState("RECORDING")
    recorder.start(wavFile)
  }

  fun stopPttAndRun() {
    Log.d(tag, "stopPttAndRun()")
    recorder.stop()
    onState("THINKING")

    io.execute {
      try {
        Log.d(tag, "ensureModel()")
        val whisperModel = ensureModel()
        Log.d(tag, "whisper.transcribeWav()")
        val transcript = whisper.transcribeWav(
          wavPath = wavFile.absolutePath,
          modelPath = whisperModel.absolutePath
        ).trim()

        main.post { onTranscript(transcript.ifEmpty { "(empty)" }) }

        val answer = if (transcript.isBlank()) {
          "Ich habe nichts verstanden."
        } else {
          main.post { onAnswer("Denke nach…") }
          mlc.ensureBaseModelAvailable { status ->
            main.post { onState(status) }
          }
          Log.d(tag, "mlc.generateDeShort()")
          mlc.generateDeShortStream(transcript, timeoutMs = 120_000) { partial ->
            main.post { onAnswer(partial) }
          }
        }

        main.post { onAnswer(answer) }
        main.post {
          onState("SPEAKING")
          tts.speak(answer)
          onState("IDLE")
        }
      } catch (t: Throwable) {
        Log.e(tag, "Error in stopPttAndRun", t)
        val msg = "Fehler: ${t.message ?: t.javaClass.simpleName}"
        main.post { onAnswer(msg) }
        main.post {
          onState("SPEAKING")
          tts.speak(msg)
          onState("IDLE")
        }
      }
    }
  }

  fun downloadModel(url: String) {
    io.execute {
      try {
        main.post { onState("DOWNLOADING MODEL") }
        val modelId = mlc.downloadAndInstallModel(url) { status ->
          main.post { onState(status) }
        }
        main.post { onAnswer("Model installed: $modelId") }
        main.post { onState("IDLE") }
      } catch (t: Throwable) {
        Log.e(tag, "Model download failed", t)
        val msg = "Download failed: ${t.message ?: t.javaClass.simpleName}"
        main.post { onAnswer(msg) }
        main.post { onState("IDLE") }
      }
    }
  }

  private fun ensureModel(): File {
    if (modelFile.exists() && modelFile.length() > 1_000_000 && hasGgmlMagic(modelFile)) {
      return modelFile
    }
    main.post { onState("INSTALLING MODEL") }
    try {
      modelFile.parentFile?.mkdirs()
      if (modelFile.exists() && !modelFile.delete()) {
        Log.w(tag, "Failed to delete stale model at ${modelFile.absolutePath}")
        throw IllegalStateException("Unable to delete stale model file")
      }
      val assetPath = "models/ggml-base.bin"
      try {
        Log.d(tag, "Copying asset $assetPath to ${modelFile.absolutePath}")
        context.assets.open(assetPath).use { input ->
          modelFile.outputStream().use { output ->
            input.copyTo(output)
          }
        }
      } catch (t: Throwable) {
        val available = try {
          context.assets.list("models")?.joinToString() ?: "none"
        } catch (listErr: Throwable) {
          "list failed: ${listErr.message}"
        }
        throw IllegalStateException(
          "Asset copy failed: $assetPath (assets/models=$available): ${t.javaClass.simpleName}: ${t.message}",
          t
        )
      }
    } catch (t: Throwable) {
      Log.e(tag, "ensureModel failed", t)
      throw IllegalStateException("Model copy failed: ${t.message}", t)
    }
    main.post { onState("THINKING") }
    if (!modelFile.exists() || modelFile.length() <= 1_000_000) {
      throw IllegalStateException("Model copy failed: file missing or too small")
    }
    if (!hasGgmlMagic(modelFile)) {
      throw IllegalStateException("Model invalid after copy (missing ggml header)")
    }
    return modelFile
  }

  private fun hasGgmlMagic(file: File): Boolean {
    return try {
      file.inputStream().use { stream ->
        val magic = ByteArray(4)
        val read = stream.read(magic)
        val le = magic[0] == 'l'.code.toByte() && magic[1] == 'm'.code.toByte() &&
          magic[2] == 'g'.code.toByte() && magic[3] == 'g'.code.toByte()
        val be = magic[0] == 'g'.code.toByte() && magic[1] == 'g'.code.toByte() &&
          magic[2] == 'm'.code.toByte() && magic[3] == 'l'.code.toByte()
        read == 4 && (le || be)
      }
    } catch (_: Throwable) {
      false
    }
  }

  fun shutdown() {
    try {
      recorder.stop()
    } finally {
      tts.shutdown()
    }
  }
}
