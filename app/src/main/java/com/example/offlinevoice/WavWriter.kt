package com.example.offlinevoice

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {
  fun pcm16ToWav(pcmFile: File, wavFile: File, sampleRate: Int, channels: Int) {
    val pcmDataSize = pcmFile.length().toInt()
    val byteRate = sampleRate * channels * 2
    val blockAlign = channels * 2
    val wavDataSize = 36 + pcmDataSize

    FileInputStream(pcmFile).use { fis ->
      FileOutputStream(wavFile).use { fos ->
        fos.write("RIFF".toByteArray())
        fos.write(leInt(wavDataSize))
        fos.write("WAVE".toByteArray())

        fos.write("fmt ".toByteArray())
        fos.write(leInt(16))
        fos.write(leShort(1))
        fos.write(leShort(channels.toShort()))
        fos.write(leInt(sampleRate))
        fos.write(leInt(byteRate))
        fos.write(leShort(blockAlign.toShort()))
        fos.write(leShort(16))

        fos.write("data".toByteArray())
        fos.write(leInt(pcmDataSize))

        val buf = ByteArray(64 * 1024)
        while (true) {
          val r = fis.read(buf)
          if (r <= 0) break
          fos.write(buf, 0, r)
        }
      }
    }
  }

  private fun leInt(v: Int): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

  private fun leShort(v: Short): ByteArray =
    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
}
