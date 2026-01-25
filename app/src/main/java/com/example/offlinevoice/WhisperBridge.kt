package com.example.offlinevoice

class WhisperBridge {
  companion object { init { System.loadLibrary("native-lib") } }
  external fun transcribeWav(wavPath: String, modelPath: String): String
}
