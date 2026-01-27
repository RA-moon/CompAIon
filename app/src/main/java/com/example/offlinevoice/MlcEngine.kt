package com.example.offlinevoice

import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

class MlcEngine(private val context: Context) {

  private val tag = "CompAIon.MlcEngine"

  data class ModelPaths(val root: File, val modelDir: File, val modelLib: String)

  private companion object {
    private const val MODELS_DIR = "models/llm"
    private const val CHAT_CONFIG = "mlc-chat-config.json"
    private const val APP_CONFIG = "mlc-app-config.json"
    private const val MODEL_LIB_TXT = "model_lib.txt"
  }

  private val engine = MLCEngine()
  private var loadedKey: String? = null
  private var nativeRuntimeLoaded = false
  private val baseModelId = BuildConfig.MLC_BASE_MODEL_ID
  private val assetPackName = BuildConfig.MLC_ASSET_PACK
  private val fallbackZipUrl = BuildConfig.MLC_FALLBACK_ZIP_URL

  private fun modelRoots(): List<File> {
    val roots = listOf(
      File(context.getExternalFilesDir(null), MODELS_DIR),
      File(context.filesDir, MODELS_DIR)
    )
    roots.forEach { it.mkdirs() }
    return roots
  }

  private fun discoverModelCandidates(roots: List<File>): List<Pair<File, File>> {
    return roots.flatMap { root ->
      root.listFiles()
        ?.filter { it.isDirectory && File(it, CHAT_CONFIG).exists() }
        ?.map { modelDir -> root to modelDir }
        .orEmpty()
    }
  }

  private fun selectModelCandidate(
    candidates: List<Pair<File, File>>,
    preferredIds: List<String>
  ): Pair<File, File> {
    return preferredIds.firstNotNullOfOrNull { preferredId ->
      candidates.firstOrNull { (_, dir) -> dir.name == preferredId }
    } ?: candidates.first()
  }

  fun resolveModel(): ModelPaths {
    val roots = modelRoots()
    ensureBundledModelsExtracted()
    roots.forEach { Log.d(tag, "resolveModel root=${it.absolutePath}") }
    val candidates = discoverModelCandidates(roots)
    if (candidates.isEmpty()) {
      throw IllegalStateException(
        "Kein MLC-Modell gefunden.\n" +
          "Erwartet:\n" +
          "1) /Android/data/${context.packageName}/files/$MODELS_DIR/<model>/$CHAT_CONFIG\n" +
          "2) ${context.filesDir}/$MODELS_DIR/<model>/$CHAT_CONFIG"
      )
    }
    val preferredIds = roots.flatMap { preferredModelIds(it) }.distinct()
    val (root, modelDir) = selectModelCandidate(candidates, preferredIds)
    if (preferredIds.isNotEmpty()) {
      Log.d(tag, "resolveModel preferredIds=${preferredIds.joinToString()}")
    }
    Log.d(tag, "resolveModel modelDir=${modelDir.absolutePath}")
    val modelLib = resolveModelLib(root, modelDir)
    Log.d(tag, "resolveModel modelLib=$modelLib")
    return ModelPaths(root, modelDir, modelLib)
  }

  private fun copyAssetDir(assetPath: String, destPath: File) {
    val assets = context.assets
    val children = assets.list(assetPath)
    if (children == null || children.isEmpty()) {
      destPath.parentFile?.mkdirs()
      assets.open(assetPath).use { input ->
        destPath.outputStream().use { output ->
          input.copyTo(output)
        }
      }
      return
    }
    destPath.mkdirs()
    for (child in children) {
      copyAssetDir("$assetPath/$child", File(destPath, child))
    }
  }

  fun loadModel(modelPaths: ModelPaths = resolveModel()) {
    val key = "${modelPaths.modelDir.absolutePath}::${modelPaths.modelLib}"
    if (loadedKey == key) return
    Log.i(tag, "loadModel modelDir=${modelPaths.modelDir.absolutePath} modelLib=${modelPaths.modelLib}")
    engine.unload()
    ensureNativeRuntimePresent()
    ensureModelLibPresent(modelPaths.modelLib, modelPaths.modelDir)
    engine.reload(modelPaths.modelDir.absolutePath, modelPaths.modelLib)
    loadedKey = key
  }

  fun ensureBaseModelAvailable(onStatus: (String) -> Unit) {
    val internalRoot = File(context.filesDir, MODELS_DIR)
    if (File(internalRoot, baseModelId).resolve(CHAT_CONFIG).exists()) return
    ensureBundledModelsExtracted()
    if (File(internalRoot, baseModelId).resolve(CHAT_CONFIG).exists()) return
    if (fallbackZipUrl.isNotBlank()) {
      downloadAndInstallModel(fallbackZipUrl, onStatus)
    } else {
      error(
        "Basismodell fehlt. Kein Auto-Download konfiguriert (MLC_FALLBACK_ZIP_URL)."
      )
    }
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
    for (cfg in appConfigCandidates(root)) {
      if (!cfg.exists()) continue
      Log.d(tag, "resolveModelLib reading ${cfg.absolutePath}")
      resolveModelLibFromJson(cfg.readText(), modelId)?.let { return it }
    }
    runCatching {
      context.assets.open(APP_CONFIG).bufferedReader().use { it.readText() }
    }.getOrNull()?.let { jsonText ->
      Log.d(tag, "resolveModelLib reading assets/$APP_CONFIG")
      resolveModelLibFromJson(jsonText, modelId)?.let { return it }
    }
    val fallback = File(modelDir, MODEL_LIB_TXT)
    if (fallback.exists()) {
      Log.d(tag, "resolveModelLib using ${fallback.absolutePath}")
      return fallback.readText().trim()
    }
    throw IllegalStateException(
      "Model lib nicht gefunden.\n" +
        "Lege eine $APP_CONFIG mit model_lib an oder ${modelDir.name}/$MODEL_LIB_TXT."
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

  private fun appConfigCandidates(root: File? = null): List<File> {
    val candidates = mutableListOf<File>()
    if (root != null) candidates += File(root, APP_CONFIG)
    candidates += File(context.getExternalFilesDir(null), APP_CONFIG)
    candidates += File(context.filesDir, APP_CONFIG)
    return candidates
  }

  private fun preferredModelIds(root: File): List<String> {
    val ids = mutableListOf<String>()
    for (cfg in appConfigCandidates(root)) {
      if (!cfg.exists()) continue
      runCatching { parseModelIds(cfg.readText()) }
        .getOrDefault(emptyList())
        .forEach { ids += it }
    }
    runCatching {
      context.assets.open(APP_CONFIG).bufferedReader().use { it.readText() }
    }.getOrNull()?.let { jsonText ->
      ids += parseModelIds(jsonText)
    }
    return ids
  }

  private fun parseModelIds(jsonText: String): List<String> {
    val json = JSONObject(jsonText)
    val list = json.optJSONArray("model_list") ?: return emptyList()
    val ids = mutableListOf<String>()
    for (i in 0 until list.length()) {
      val id = list.getJSONObject(i).optString("model_id")
      if (id.isNotBlank()) ids += id
    }
    return ids
  }

  private fun ensureNativeRuntimePresent() {
    try {
      Log.d(tag, "System.loadLibrary(tvm4j_runtime_packed)")
      System.loadLibrary("tvm4j_runtime_packed")
      nativeRuntimeLoaded = true
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
    nativeRuntimeLoaded = true
  }

  private fun ensureModelLibPresent(modelLib: String, modelDir: File) {
    try {
      Log.d(tag, "System.loadLibrary($modelLib)")
      System.loadLibrary(modelLib)
      return
    } catch (_: UnsatisfiedLinkError) {
      Log.w(tag, "System.loadLibrary($modelLib) failed, checking nativeLibraryDir")
    }
    val localLib = File(modelDir, "lib$modelLib.so")
    if (localLib.exists()) {
      Log.d(tag, "System.load(${localLib.absolutePath})")
      System.load(localLib.absolutePath)
      return
    }
    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val expected = File(nativeDir, "lib$modelLib.so")
    if (!expected.exists()) {
      val bundledRuntime = File(nativeDir, "libtvm4j_runtime_packed.so")
      if (nativeRuntimeLoaded || bundledRuntime.exists()) {
        Log.w(
          tag,
          "Model lib ${expected.name} not found; assuming it is bundled into ${bundledRuntime.name}"
        )
        return
      }
      Log.e(tag, "Missing model lib at ${expected.absolutePath}")
      throw IllegalStateException(
        "Kompilierte Model-Lib fehlt: ${expected.name}.\n" +
          "Führe im mlc-llm Repo `mlc_llm package` für $modelLib aus und installiere dann erneut."
      )
    }
  }

  fun downloadAndInstallModel(
    url: String,
    onStatus: (String) -> Unit
  ): String {
    require(url.startsWith("http://") || url.startsWith("https://")) {
      "Nur http/https URLs sind erlaubt."
    }
    val target = File(context.cacheDir, "mlc-model-download.zip")
    downloadFile(url, target, onStatus)
    return installModelFromZip(target, onStatus)
  }

  private fun downloadFile(url: String, target: File, onStatus: (String) -> Unit) {
    onStatus("DOWNLOADING MODEL")
    val connection = URL(url).openConnection()
    val total = connection.contentLengthLong
    var downloaded = 0L
    var lastUpdate = 0L
    connection.getInputStream().use { input ->
      FileOutputStream(target).use { output ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
          val read = input.read(buffer)
          if (read <= 0) break
          output.write(buffer, 0, read)
          downloaded += read
          val now = SystemClock.elapsedRealtime()
          if (now - lastUpdate > 500) {
            lastUpdate = now
            if (total > 0) {
              val pct = (downloaded * 100 / total).toInt()
              onStatus("DOWNLOADING MODEL ($pct%)")
            } else {
              onStatus("DOWNLOADING MODEL (${downloaded / (1024 * 1024)}MB)")
            }
          }
        }
      }
    }
  }

  private fun installModelFromZip(zipFile: File, onStatus: (String) -> Unit): String {
    onStatus("INSTALLING MODEL")
    val tempRoot = File(context.cacheDir, "mlc-model-${SystemClock.elapsedRealtime()}")
    if (tempRoot.exists()) tempRoot.deleteRecursively()
    tempRoot.mkdirs()
    unzip(zipFile, tempRoot)
    val modelDir = findModelDir(tempRoot)
      ?: throw IllegalStateException("Kein $CHAT_CONFIG im Download gefunden.")
    val modelId = readModelId(modelDir) ?: modelDir.name
    val targetDir = File(context.filesDir, "$MODELS_DIR/$modelId")
    if (targetDir.exists()) targetDir.deleteRecursively()
    copyDir(modelDir, targetDir)
    val modelLib = readModelLib(targetDir) ?: inferModelLibFromSo(targetDir)
    if (modelLib != null) {
      File(targetDir, MODEL_LIB_TXT).writeText(modelLib)
    }
    maybePromoteModelLib(targetDir, modelLib)
    writePreferredAppConfig(modelId, modelLib)
    pruneModels(setOf(baseModelId, modelId))
    loadedKey = null
    onStatus("MODEL READY")
    return modelId
  }

  private fun unzip(zipFile: File, dest: File) {
    ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
      val destPath = dest.canonicalFile.toPath()
      var entry = zis.nextEntry
      while (entry != null) {
        val outFile = File(dest, entry.name)
        val outPath = outFile.canonicalFile.toPath()
        check(outPath.startsWith(destPath)) { "Unsafe zip entry: ${entry.name}" }
        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          FileOutputStream(outFile).use { output ->
            zis.copyTo(output)
          }
        }
        zis.closeEntry()
        entry = zis.nextEntry
      }
    }
  }

  private fun findModelDir(root: File): File? {
    if (File(root, CHAT_CONFIG).exists()) return root
    root.walkTopDown().forEach { file ->
      if (file.name == CHAT_CONFIG) {
        return file.parentFile
      }
    }
    return null
  }

  private fun readModelId(modelDir: File): String? {
    val cfg = File(modelDir, CHAT_CONFIG)
    if (!cfg.exists()) return null
    val json = JSONObject(cfg.readText())
    return json.optString("model_id").takeIf { it.isNotBlank() }
  }

  private fun readModelLib(modelDir: File): String? {
    val txt = File(modelDir, MODEL_LIB_TXT)
    if (txt.exists()) return txt.readText().trim().takeIf { it.isNotBlank() }
    val cfg = File(modelDir, CHAT_CONFIG)
    if (!cfg.exists()) return null
    val json = JSONObject(cfg.readText())
    return json.optString("model_lib").takeIf { it.isNotBlank() }
  }

  private fun inferModelLibFromSo(modelDir: File): String? {
    val so = modelDir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".so") }
    return so?.name?.removePrefix("lib")?.removeSuffix(".so")
  }

  private fun maybePromoteModelLib(modelDir: File, modelLib: String?) {
    if (modelLib.isNullOrBlank()) return
    val expected = File(modelDir, "lib$modelLib.so")
    if (expected.exists()) return
    val found = modelDir.walkTopDown().firstOrNull { it.isFile && it.name == "lib$modelLib.so" }
      ?: modelDir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".so") }
      ?: return
    found.copyTo(expected, overwrite = true)
  }

  private fun writePreferredAppConfig(modelId: String, modelLib: String?) {
    val root = File(context.filesDir, MODELS_DIR)
    root.mkdirs()
    val config = File(root, APP_CONFIG)
    val entry = JSONObject().apply {
      put("model_id", modelId)
      if (!modelLib.isNullOrBlank()) {
        put("model_lib", modelLib)
      }
    }
    val list = JSONObject().apply {
      put("model_list", org.json.JSONArray().put(entry))
    }
    config.writeText(list.toString(2))
  }

  private fun pruneModels(keepIds: Set<String>) {
    val roots = modelRoots()
    roots.forEach { root ->
      root.listFiles()
        ?.filter { it.isDirectory }
        ?.forEach { dir ->
          if (!keepIds.contains(dir.name)) {
            dir.deleteRecursively()
          }
        }
    }
  }

  private fun ensureBundledModelsExtracted() {
    val internalRoot = File(context.filesDir, MODELS_DIR)
    val basePresent = File(internalRoot, baseModelId)
      .resolve(CHAT_CONFIG)
      .exists()
    if (basePresent) return
    val packModels = listAssetPackModels()
    if (packModels.isNotEmpty()) {
      for (model in packModels) {
        val targetDir = File(internalRoot, model.name)
        if (File(targetDir, CHAT_CONFIG).exists()) continue
        Log.i(tag, "Extracting asset pack model ${model.name} to ${targetDir.absolutePath}")
        copyDir(model, targetDir)
      }
      return
    }
    val bundledModels = runCatching { context.assets.list("mlc/models") }.getOrNull()
      ?.filter { it.isNotBlank() }
      .orEmpty()
    if (bundledModels.isEmpty()) return
    for (modelId in bundledModels) {
      val targetDir = File(internalRoot, modelId)
      if (File(targetDir, CHAT_CONFIG).exists()) continue
      Log.i(tag, "Extracting bundled model $modelId to ${targetDir.absolutePath}")
      copyAssetDir("mlc/models/$modelId", targetDir)
    }
  }

  private fun listAssetPackModels(): List<File> {
    return try {
      val manager = AssetPackManagerFactory.getInstance(context)
      val location = manager.getPackLocation(assetPackName) ?: return emptyList()
      val assetsPath = location.assetsPath() ?: return emptyList()
      val root = File(assetsPath, "mlc/models")
      root.listFiles()
        ?.filter { it.isDirectory && File(it, CHAT_CONFIG).exists() }
        .orEmpty()
    } catch (t: Throwable) {
      Log.w(tag, "Asset pack lookup failed", t)
      emptyList()
    }
  }

  private fun copyDir(source: File, target: File) {
    if (source.isDirectory) {
      target.mkdirs()
      source.listFiles()?.forEach { child ->
        copyDir(child, File(target, child.name))
      }
      return
    }
    target.parentFile?.mkdirs()
    source.inputStream().use { input ->
      target.outputStream().use { output ->
        input.copyTo(output)
      }
    }
  }
}
