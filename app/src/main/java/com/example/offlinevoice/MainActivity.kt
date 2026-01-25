package com.example.offlinevoice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.util.Log
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Camera
import com.google.android.filament.View
import com.google.android.filament.gltfio.MaterialProvider
import com.example.offlinevoice.databinding.ActivityMainBinding
import io.github.sceneview.SceneView
import io.github.sceneview.components.PRIORITY_LAST
import io.github.sceneview.math.Color
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import java.io.File
import kotlin.math.max

class MainActivity : AppCompatActivity() {

  private lateinit var binding: ActivityMainBinding
  private lateinit var controller: AssistantController
  private val inputState = InputState()
  private var lastViewportWidth = 0
  private var lastViewportHeight = 0
  private var orthoHalfWidth = 5f
  private var orthoHalfHeight = 5f
  private var valuesBaseSize = 1f
  private var menuBaseSize = 1f
  private var lastFrameNanos = 0L
  private var sheenHue = 0f
  private var colorHue = 0f
  private var valuesMaterial: MaterialInstance? = null
  private var menuMaterial: MaterialInstance? = null
  private val valuesScaleMultiplier = 7.4f
  private val menuScaleMultiplier = 1.25f
  private val menuRelativeOffset = 2f
  private val menuDepth = 3.1f

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setupValuesScene()

    controller = AssistantController(
      context = this,
      onState = { binding.stateText.text = it },
      onTranscript = { binding.transcriptText.text = it.ifBlank { "Waiting for audio..." } },
      onAnswer = { binding.answerText.text = it.ifBlank { "Ready when you are." } }
    )

    ensureAudioPermission()

    binding.legalTermsLinkText.setOnClickListener {
      openLegalPdf()
    }

    binding.pttButton.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> controller.startPtt()
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> controller.stopPttAndRun()
      }
      true
    }

    binding.root.setOnTouchListener { _, event ->
      val width = binding.root.width.toFloat().coerceAtLeast(1f)
      val height = binding.root.height.toFloat().coerceAtLeast(1f)
      val normalizedX = (event.x / width) * 2f - 1f
      val normalizedY = 1f - (event.y / height) * 2f
      inputState.targetX = normalizedX
      inputState.targetY = normalizedY
      inputState.lastInputMs = System.currentTimeMillis()
      false
    }
  }

  override fun onDestroy() {
    controller.shutdown()
    super.onDestroy()
  }

  private fun ensureAudioPermission() {
    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
    if (!granted) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
    }
  }

  private fun openLegalPdf() {
    val cachedFile = File(cacheDir, "astralpirates_terms_privacy.pdf")
    if (!cachedFile.exists() || cachedFile.length() == 0L) {
      copyLegalPdfToCache(cachedFile)
    }

    val uri = FileProvider.getUriForFile(
      this,
      "${BuildConfig.APPLICATION_ID}.fileprovider",
      cachedFile
    )
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/pdf")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    try {
      startActivity(Intent.createChooser(viewIntent, getString(R.string.legal_terms_link)))
    } catch (e: ActivityNotFoundException) {
      Toast.makeText(this, R.string.legal_terms_open_error, Toast.LENGTH_LONG).show()
      Log.w("MainActivity", "No PDF viewer available", e)
    }
  }

  private fun copyLegalPdfToCache(targetFile: File) {
    resources.openRawResource(R.raw.astralpirates_terms_privacy).use { input ->
      targetFile.outputStream().use { output ->
        input.copyTo(output)
      }
    }
  }

  private fun setupValuesScene() {
    val sceneView = binding.valuesScene
    sceneView.isClickable = false
    sceneView.isFocusable = false
    sceneView.setZOrderMediaOverlay(true)
    sceneView.holder.setFormat(PixelFormat.TRANSLUCENT)
    sceneView.view.blendMode = View.BlendMode.TRANSLUCENT
    sceneView.view.antiAliasing = View.AntiAliasing.FXAA
    sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply {
      clear = true
      clearColor = floatArrayOf(0f, 0f, 0f, 0f)
    }
    sceneView.environment = SceneView.createEnvironment(sceneView.environmentLoader, isOpaque = false)

    tuneAstralLighting(sceneView)

    val valuesInstance = sceneView.modelLoader.createModelInstance("models/values.glb")
    val valuesNode = ModelNode(
      modelInstance = valuesInstance,
      centerOrigin = Position(0f, 0f, 0f),
    ).apply {
      isShadowCaster = false
      isShadowReceiver = false
      position = Position(0f, 0f, 0f)
    }
    valuesBaseSize = maxExtent(valuesNode)

    val menuInstance = sceneView.modelLoader.createModelInstance("models/menu.glb")
    val menuNode = ModelNode(
      modelInstance = menuInstance,
      centerOrigin = Position(0f, 0f, 0f),
    ).apply {
      isShadowCaster = false
      isShadowReceiver = false
      parent = sceneView.cameraNode
    }
    menuBaseSize = maxExtent(menuNode)

    valuesMaterial = buildValuesMaterial(sceneView)
    valuesMaterial?.let { valuesNode.setMaterialInstance(it) }
    menuMaterial = buildMenuMaterial(sceneView)
    menuMaterial?.let { menuNode.setMaterialInstance(it) }
    menuNode.renderableNodes.forEach { it.setPriority(PRIORITY_LAST) }
    sceneView.childNodes = listOf(valuesNode, menuNode)

    sceneView.cameraNode.position = Position(0f, 0f, 10f)
    sceneView.cameraNode.lookAt(Position(0f, 0f, 0f))

    var startNanos = 0L
    sceneView.onFrame = { frameTimeNanos ->
      if (startNanos == 0L) startNanos = frameTimeNanos
      val seconds = (frameTimeNanos - startNanos) / 1_000_000_000f
      val dt = if (lastFrameNanos == 0L) 0f
      else (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
      lastFrameNanos = frameTimeNanos

      updateInput()
      val viewportChanged = updateCameraProjection(sceneView)
      if (viewportChanged) {
        updateValuesScale(valuesNode)
        updateMenuPlacement(sceneView, menuNode)
      }
      updateValuesMaterial(dt)

      val dist = kotlin.math.min(1f, kotlin.math.hypot(inputState.currentX, inputState.currentY))
      val yRotation = (seconds * 14f) % 360f + inputState.currentX * 18f * dist
      val xRotation = (seconds * 6f) % 360f + inputState.currentY * 12f * dist
      valuesNode.rotation = Rotation(xRotation, yRotation, 0f)

      val menuTiltX = -inputState.currentY * 12f
      val menuTiltY = -inputState.currentX * 18f
      menuNode.rotation = Rotation(menuTiltX, menuTiltY, 0f)
    }
  }

  private fun tuneAstralLighting(sceneView: SceneView) {
    sceneView.indirectLight?.intensity = 62_000f
    sceneView.mainLightNode?.apply {
      intensity = 54_000f
      color = Color(0.92f, 0.96f, 1f, 1f)
      lightDirection = Direction(x = 0.3f, y = -0.6f, z = -1f)
    }
  }

  private fun buildValuesMaterial(sceneView: SceneView): MaterialInstance {
    val key = MaterialProvider.MaterialKey().apply {
      doubleSided = true
      unlit = false
      alphaMode = 2
      hasTransmission = true
      hasClearCoat = true
      hasSheen = true
      hasIOR = true
    }
    val instance = sceneView.materialLoader.createUbershaderInstance(
      config = key,
      label = "values"
    ) ?: sceneView.materialLoader.createColorInstance(
      color = Color(0.45f, 0.62f, 0.9f, 0.65f),
      metallic = 0.6f,
      roughness = 0.08f,
      reflectance = 0.85f
    )
    logMaterialParams(instance)
    instance.safeSetParameter("baseColorFactor", 0.35f, 0.55f, 0.95f, 0.65f)
    instance.safeSetParameter("metallicFactor", 0.72f)
    instance.safeSetParameter("roughnessFactor", 0.06f)
    instance.safeSetParameter("emissiveFactor", 0.08f, 0.16f, 0.3f)
    instance.safeSetParameter("clearCoatFactor", 1f)
    instance.safeSetParameter("clearCoatRoughnessFactor", 0.04f)
    instance.safeSetParameter("transmissionFactor", 0.9f)
    instance.safeSetParameter("ior", 1.45f)
    instance.safeSetParameter("thicknessFactor", 0.5f)
    instance.safeSetParameter("attenuationDistance", 1.2f)
    instance.safeSetParameter("sheenRoughnessFactor", 0.15f)
    return instance
  }

  private fun buildMenuMaterial(sceneView: SceneView): MaterialInstance {
    val key = MaterialProvider.MaterialKey().apply {
      doubleSided = true
      unlit = true
      alphaMode = 0
    }
    val instance = sceneView.materialLoader.createUbershaderInstance(
      config = key,
      label = "menu"
    ) ?: sceneView.materialLoader.createColorInstance(
      color = Color(0.91f, 0.9f, 0.88f, 1f),
      metallic = 0.05f,
      roughness = 0.7f,
      reflectance = 0.4f
    )
    logMaterialParams(instance)
    instance.safeSetParameter("baseColorFactor", 0.91f, 0.9f, 0.88f, 1f)
    instance.safeSetParameter("emissiveFactor", 0f, 0f, 0f)
    instance.safeSetParameter("metallicFactor", 0.05f)
    instance.safeSetParameter("roughnessFactor", 0.7f)
    return instance
  }

  private fun updateInput() {
    val now = System.currentTimeMillis()
    if (now - inputState.lastInputMs > 600) {
      inputState.targetX *= 0.9f
      inputState.targetY *= 0.9f
    }
    val ease = 0.08f
    inputState.currentX += (inputState.targetX - inputState.currentX) * ease
    inputState.currentY += (inputState.targetY - inputState.currentY) * ease
  }

  private fun updateCameraProjection(sceneView: SceneView): Boolean {
    val width = sceneView.width
    val height = sceneView.height
    if (width <= 0 || height <= 0) return false
    if (width == lastViewportWidth && height == lastViewportHeight) return false
    lastViewportWidth = width
    lastViewportHeight = height
    val aspect = width.toFloat() / height.toFloat()
    orthoHalfHeight = 5f
    orthoHalfWidth = orthoHalfHeight * aspect
    sceneView.cameraNode.setProjection(
      Camera.Projection.ORTHO,
      -orthoHalfWidth.toDouble(),
      orthoHalfWidth.toDouble(),
      -orthoHalfHeight.toDouble(),
      orthoHalfHeight.toDouble(),
      0.1,
      50.0
    )
    return true
  }

  private fun updateValuesScale(valuesNode: ModelNode) {
    if (valuesBaseSize <= 0f) return
    val widthWorld = orthoHalfWidth * 2f
    val heightWorld = orthoHalfHeight * 2f
    val targetWorld = kotlin.math.min(widthWorld / 3f, heightWorld / 3f)
    val scale = (targetWorld / valuesBaseSize) * valuesScaleMultiplier
    valuesNode.scale = Scale(scale, scale, scale)
  }

  private fun updateValuesMaterial(dt: Float) {
    val material = valuesMaterial ?: return
    if (dt <= 0f) return
    sheenHue = (sheenHue + dt * 0.08f) % 1f
    colorHue = (colorHue + dt * 0.02f) % 1f
    val sheen = hslToRgb(sheenHue, 1f, 0.5f)
    val base = hslToRgb(colorHue, 0.3f, 0.12f)
    material.safeSetParameter("sheenColorFactor", sheen[0], sheen[1], sheen[2])
    material.safeSetParameter("baseColorFactor", base[0], base[1], base[2], 0.65f)
    material.safeSetParameter("emissiveFactor", base[0] * 0.12f, base[1] * 0.2f, base[2] * 0.3f)
  }

  private fun updateMenuPlacement(sceneView: SceneView, menuNode: ModelNode) {
    val width = sceneView.width
    val height = sceneView.height
    if (width <= 0 || height <= 0 || menuBaseSize <= 0f) return
    val iconPx = iconSize(width.toFloat(), height.toFloat())
    val unitsPerPixelX = (orthoHalfWidth * 2f) / width
    val unitsPerPixelY = (orthoHalfHeight * 2f) / height
    val desiredWorld = iconPx * unitsPerPixelY
    val scale = (desiredWorld / menuBaseSize) * menuScaleMultiplier
    menuNode.scale = Scale(scale, scale, scale)

    val marginPx = iconPx * menuRelativeOffset
    val shiftPxX = (width / 2f) - marginPx
    val shiftPxY = (height / 2f) - marginPx
    val shiftWorldX = shiftPxX * unitsPerPixelX
    val shiftWorldY = shiftPxY * unitsPerPixelY
    menuNode.position = Position(shiftWorldX, shiftWorldY, -menuDepth)
  }

  private fun maxExtent(node: ModelNode): Float {
    val extents = node.extents
    return max(extents.x, max(extents.y, extents.z))
  }

  private fun iconSize(viewWidth: Float, viewHeight: Float): Float {
    val u1 = 320f
    val u2 = 1440f
    val s1 = 24f
    val s2 = 32f
    val q = 1.15f
    val sMin = 20f
    val sMax = 36f
    val u = kotlin.math.min(viewWidth, viewHeight).coerceAtLeast(1f)
    val ratio = (u - u1) / (u2 - u1)
    val scaled = q * (s1 + (s2 - s1) * ratio)
    return scaled.coerceIn(sMin, sMax)
  }

  private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
    if (s == 0f) return floatArrayOf(l, l, l)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    val r = hueToRgb(p, q, h + 1f / 3f)
    val g = hueToRgb(p, q, h)
    val b = hueToRgb(p, q, h - 1f / 3f)
    return floatArrayOf(r, g, b)
  }

  private fun hueToRgb(p: Float, q: Float, tIn: Float): Float {
    var t = tIn
    if (t < 0f) t += 1f
    if (t > 1f) t -= 1f
    return when {
      t < 1f / 6f -> p + (q - p) * 6f * t
      t < 1f / 2f -> q
      t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
      else -> p
    }
  }

  private fun logMaterialParams(material: MaterialInstance) {
    if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
      return
    }
    val key = material.name.ifBlank { material.material.name }
    if (!materialLog.add(key)) return
    val params = material.material.parameters.joinToString { it.name }
    Log.d("CompAIon", "Material[$key] params: $params")
  }
}

private val materialLog = mutableSetOf<String>()

private data class InputState(
  var targetX: Float = 0f,
  var targetY: Float = 0f,
  var currentX: Float = 0f,
  var currentY: Float = 0f,
  var lastInputMs: Long = 0L,
)

private fun MaterialInstance.safeSetParameter(name: String, vararg values: Float) {
  try {
    if (!material.hasParameter(name)) return
    when (values.size) {
      1 -> setParameter(name, values[0])
      2 -> setParameter(name, values[0], values[1])
      3 -> setParameter(name, values[0], values[1], values[2])
      4 -> setParameter(name, values[0], values[1], values[2], values[3])
    }
  } catch (_: Throwable) {
    // Ignore missing params for GLB materials.
  }
}
