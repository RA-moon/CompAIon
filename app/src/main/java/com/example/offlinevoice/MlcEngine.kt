package com.example.offlinevoice

import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

class MlcEngine(private val context: Context) {

  private val tag = "CompAIon.MlcEngine"

  data class ModelPaths(val root: File, val modelDir: File, val modelLib: String)

  private val engine = MLCEngine()
  private var loadedKey: String? = null

  private fun modelRoots(): List<File> {
    val roots = listOf(
      File(context.getExternalFilesDir(null), "models/llm"),
      File(context.filesDir, "models/llm")
    )
    roots.forEach { it.mkdirs() }
    return roots
  }

  fun resolveModel(): ModelPaths {
    val roots = modelRoots()
    roots.forEach { Log.d(tag, "resolveModel root=${it.absolutePath}") }
    val match = roots.firstNotNullOfOrNull { root ->
      root.listFiles()
        ?.firstOrNull { it.isDirectory && File(it, "mlc-chat-config.json").exists() }
        ?.let { modelDir -> root to modelDir }
    } ?: throw IllegalStateException(
      "Kein MLC-Modell gefunden.\n" +
        "Erwartet:\n" +
        "1) /Android/data/${context.packageName}/files/models/llm/<model>/mlc-chat-config.json\n" +
        "2) ${context.filesDir}/models/llm/<model>/mlc-chat-config.json"
    )
    val (root, modelDir) = match
    Log.d(tag, "resolveModel modelDir=${modelDir.absolutePath}")
    val modelLib = resolveModelLib(root, modelDir)
    Log.d(tag, "resolveModel modelLib=$modelLib")
    return ModelPaths(root, modelDir, modelLib)
  }

  fun loadModel(modelPaths: ModelPaths = resolveModel()) {
    val key = "${modelPaths.modelDir.absolutePath}::${modelPaths.modelLib}"
    if (loadedKey == key) return
    Log.i(tag, "loadModel modelDir=${modelPaths.modelDir.absolutePath} modelLib=${modelPaths.modelLib}")
    engine.unload()
    ensureNativeRuntimePresent()
    engine.reload(modelPaths.modelDir.absolutePath, modelPaths.modelLib)
    loadedKey = key
  }

  fun generateDeShort(
    userText: String,
    maxNewTokens: Int = 96,
    temperature: Float = 0.6f
  ): String {
    return generateDeShortStream(userText, maxNewTokens, temperature, 120_000, null)
  }

  fun generateDeShortStream(
    userText: String,
    maxNewTokens: Int = 96,
    temperature: Float = 0.6f,
    timeoutMs: Long = 120_000,
    onPartial: ((String) -> Unit)? = null
  ): String {
    loadModel()
    val messages = listOf(
      OpenAIProtocol.ChatCompletionMessage(
        role = OpenAIProtocol.ChatCompletionRole.system,
        content = "Du bist ein Offline-Assistent. Antworte kurz und direkt auf Deutsch (max 4 Sätze)."
      ),
      OpenAIProtocol.ChatCompletionMessage(
        role = OpenAIProtocol.ChatCompletionRole.user,
        content = userText
      )
    )

    return try {
      runBlocking {
        withTimeout(timeoutMs) {
          val startAt = SystemClock.elapsedRealtime()
          val responses = engine.chat.completions.create(
            messages = messages,
            max_tokens = maxNewTokens,
            temperature = temperature,
            stream = true
          )
          val sb = StringBuilder()
          var lastUpdate = 0L
          var firstTokenAt = 0L
          for (res in responses) {
            for (choice in res.choices) {
              choice.delta.content?.let { sb.append(it.asText()) }
            }
            if (sb.isNotEmpty() && firstTokenAt == 0L) {
              firstTokenAt = SystemClock.elapsedRealtime()
              Log.d(tag, "First token after ${firstTokenAt - startAt}ms")
            }
            if (onPartial != null && sb.isNotEmpty()) {
              val now = SystemClock.elapsedRealtime()
              if (now - lastUpdate > 200) {
                onPartial(sb.toString())
                lastUpdate = now
              }
            }
          }
          val finalText = sb.toString().trim().ifEmpty { "(empty)" }
          onPartial?.invoke(finalText)
          finalText
        }
      }
    } catch (t: TimeoutCancellationException) {
      throw IllegalStateException(
        "MLC Timeout nach ${timeoutMs / 1000}s. Das Modell ist zu langsam. Verwende ein kleineres Modell.",
        t
      )
    }
  }

  private fun resolveModelLib(root: File, modelDir: File): String {
    val modelId = modelDir.name
    Log.d(tag, "resolveModelLib modelId=$modelId")
    val appConfigCandidates = listOf(
      File(root, "mlc-app-config.json"),
      File(context.getExternalFilesDir(null), "mlc-app-config.json"),
      File(context.filesDir, "mlc-app-config.json")
    )
    for (cfg in appConfigCandidates) {
      if (!cfg.exists()) continue
      Log.d(tag, "resolveModelLib reading ${cfg.absolutePath}")
      resolveModelLibFromJson(cfg.readText(), modelId)?.let { return it }
    }
    runCatching {
      context.assets.open("mlc-app-config.json").bufferedReader().use { it.readText() }
    }.getOrNull()?.let { jsonText ->
      Log.d(tag, "resolveModelLib reading assets/mlc-app-config.json")
      resolveModelLibFromJson(jsonText, modelId)?.let { return it }
    }
    val fallback = File(modelDir, "model_lib.txt")
    if (fallback.exists()) {
      Log.d(tag, "resolveModelLib using ${fallback.absolutePath}")
      return fallback.readText().trim()
    }
    throw IllegalStateException(
      "Model lib nicht gefunden.\n" +
        "Lege eine mlc-app-config.json mit model_lib an oder ${modelDir.name}/model_lib.txt."
    )
  }

  private fun resolveModelLibFromJson(jsonText: String, modelId: String): String? {
    val json = JSONObject(jsonText)
    val list = json.optJSONArray("model_list") ?: return null
    for (i in 0 until list.length()) {
      val item = list.getJSONObject(i)
      if (item.optString("model_id") == modelId) {
        val lib = item.optString("model_lib")
        if (lib.isNotBlank()) return lib
      }
    }
    return null
  }

  private fun ensureNativeRuntimePresent() {
    try {
      Log.d(tag, "System.loadLibrary(tvm4j_runtime_packed)")
      System.loadLibrary("tvm4j_runtime_packed")
      return
    } catch (_: UnsatisfiedLinkError) {
      Log.w(tag, "System.loadLibrary failed, falling back to file check")
      // Fall back to file presence check for clearer error
    }
    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val runtime = File(nativeDir, "libtvm4j_runtime_packed.so")
    if (!runtime.exists()) {
      Log.e(tag, "Missing runtime at ${runtime.absolutePath}")
      throw IllegalStateException(
        "MLC Runtime nicht gefunden.\n" +
          "Erwartet: ${runtime.absolutePath}\n" +
          "Baue mlc4j (aus mlc-llm) und stelle sicher, dass die Ausgabe in der App landet."
      )
    }
  }
}
