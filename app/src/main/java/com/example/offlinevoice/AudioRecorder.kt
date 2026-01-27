package com.example.offlinevoice

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(private val context: Context) {
  private val isRecording = AtomicBoolean(false)
  private var thread: Thread? = null
  private val tag = "CompAIon.Recorder"

  private val sampleRate = 16000
  private val channelConfig = AudioFormat.CHANNEL_IN_MONO
  private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

  fun start(outWav: File) {
    val hasPermission =
      ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPermission) {
      throw SecurityException("RECORD_AUDIO permission not granted")
    }

    val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    val bufSize = (minBuf * 2).coerceAtLeast(sampleRate)

    val rec = AudioRecord(
      MediaRecorder.AudioSource.VOICE_RECOGNITION,
      sampleRate, channelConfig, audioFormat, bufSize
    )

    val pcmTemp = File(outWav.parentFile, outWav.nameWithoutExtension + ".pcm")
    deleteIfExists(pcmTemp, "pre-record cleanup")

    isRecording.set(true)
    rec.startRecording()

    thread = Thread {
      FileOutputStream(pcmTemp).use { fos ->
        val buffer = ByteArray(bufSize)
        while (isRecording.get()) {
          val read = rec.read(buffer, 0, buffer.size)
          if (read > 0) fos.write(buffer, 0, read)
        }
      }
      rec.stop()
      rec.release()

      WavWriter.pcm16ToWav(
        pcmFile = pcmTemp,
        wavFile = outWav,
        sampleRate = sampleRate,
        channels = 1
      )
      deleteIfExists(pcmTemp, "post-convert cleanup")
    }.also { it.start() }
  }

  fun stop() {
    isRecording.set(false)
    thread?.join(1500)
    thread = null
  }

  private fun deleteIfExists(file: File, context: String) {
    if (!file.exists()) return
    if (!file.delete()) {
      Log.w(tag, "Failed to delete ${file.absolutePath} during $context")
    }
  }
}
