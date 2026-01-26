package com.example.offlinevoice

import android.Manifest
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.ColorGrading
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Camera
import com.google.android.filament.ToneMapper
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
  private var menuBaseOffset = Position(0f, 0f, 0f)
  private var menuToggleRotationDeg = 0f
  private var menuRotationAnimator: ValueAnimator? = null
  private val menuPartRotationDeg = mutableMapOf<ModelNode.RenderableNode, Float>()
  private val menuPartAnimators = mutableMapOf<ModelNode.RenderableNode, ValueAnimator>()
  private val menuPartBaseRotation = mutableMapOf<ModelNode.RenderableNode, Rotation>()
  private var menuPartTop: ModelNode.RenderableNode? = null
  private var menuPartCenter: ModelNode.RenderableNode? = null
  private var menuPartBottom: ModelNode.RenderableNode? = null
  private var menuTapDownX = 0f
  private var menuTapDownY = 0f
  private var menuTapDownTimeMs = 0L
  private var lastFrameNanos = 0L
  private var sheenHue = 0f
  private var colorHue = 0f
  private var valuesMaterial: MaterialInstance? = null
  private var menuMaterial: MaterialInstance? = null
  private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
  private val rotationVectorSensor: Sensor? by lazy {
    sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
  }
  private var gyroActive = false
  private var gyroBaselinePitchDeg: Float? = null
  private var gyroBaselineRollDeg: Float? = null
  private var gyroPointerX = 0f
  private var gyroPointerY = 0f
  private val valuesScaleMultiplier = 7.4f
  private val menuScaleMultiplier = 3.0f
  private val menuRelativeOffset = 3f
  private val menuDepthOffset = 1f
  private val cameraNear = 0.1f
  private val cameraFar = 50f
  private val menuTapTimeoutMs = 400L
  private val menuTapSlopPx by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
  private val menuPartAnimationDurationMs = 350L
  private val sheenHueSpeedMin = 0.06f
  private val sheenHueSpeedMax = 0.11f
  private val colorHueSpeedMin = 0.01f
  private val colorHueSpeedMax = 0.05f
  private val gyroGammaRangeDeg = 35f
  private val gyroBetaRangeDeg = 35f
  private val gyroMovementRatioX = 0.35f
  private val gyroMovementRatioY = 0.3f
  private val gyroSmoothing = 0.25f
  private val gyroBaselineRecenter = 0.015f
  private val valuesColorSaturation = 0.3f
  private val valuesColorLightness = 0.1f
  private val valuesAlpha = 0.06f
  private val valuesMetallic = 0.7f
  private val valuesRoughness = 0.08f
  private val valuesReflectance = 0.9f
  private val valuesIor = 1.45f
  private val valuesSheenColor = floatArrayOf(0.6392f, 0.1608f, 0.7020f)
  private val valuesSheenRoughness = 0f
  private val valuesClearCoat = 1f
  private val valuesClearCoatRoughness = 0f
  private val valuesEnvMapIntensity = 1f
  private val valuesAttenuationDistanceMultiplier = 1.6f
  private val valuesAttenuationColorStrength = 0.2f

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

    binding.downloadModelButton.setOnClickListener {
      showModelDownloadDialog()
    }

    // Drag response disabled for now; gyro input still works.
    binding.root.setOnTouchListener { _, _ -> false }
  }

  override fun onResume() {
    super.onResume()
    startGyroscope()
  }

  override fun onPause() {
    stopGyroscope()
    super.onPause()
  }

  override fun onDestroy() {
    stopGyroscope()
    menuRotationAnimator?.cancel()
    menuPartAnimators.values.forEach { it.cancel() }
    menuPartAnimators.clear()
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

  private fun showModelDownloadDialog() {
    val input = EditText(this).apply {
      hint = "https://.../model.zip"
    }
    AlertDialog.Builder(this)
      .setTitle("Download Model")
      .setMessage(
        "Provide a URL to a .zip that contains mlc-chat-config.json, model_lib.txt, " +
          "and lib<model_lib>.so."
      )
      .setView(input)
      .setPositiveButton("Download") { _, _ ->
        val url = input.text.toString().trim()
        if (url.isBlank()) {
          Toast.makeText(this, "Please enter a URL.", Toast.LENGTH_SHORT).show()
        } else {
          controller.downloadModel(url)
        }
      }
      .setNegativeButton("Cancel", null)
      .show()
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
    sceneView.view.colorGrading = ColorGrading.Builder()
      .toneMapper(ToneMapper.ACES())
      .build(sceneView.engine)
    sceneView.cameraNode.setExposure(2.0f)

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
    valuesMaterial = buildValuesMaterial(sceneView, valuesBaseSize)
    valuesMaterial?.let { valuesNode.setMaterialInstance(it) }
    valuesNode.renderableNodes.forEach {
      it.setPriority(PRIORITY_LAST)
      it.setBlendOrder(64_000)
      it.setCulling(false)
    }

    val menuInstance = sceneView.modelLoader.createModelInstance("models/menu.glb")
    val menuNode = ModelNode(
      modelInstance = menuInstance,
      centerOrigin = Position(0f, 0f, 0f),
    ).apply {
      isShadowCaster = false
      isShadowReceiver = false
    }
    val (menuSize, menuCenter) = computeRenderableBounds(menuNode)
    menuBaseSize = menuSize
    menuBaseOffset = Position(-menuCenter.x, -menuCenter.y, -menuCenter.z)
    assignMenuParts(menuNode)

    menuMaterial = buildMenuMaterial(sceneView)
    menuMaterial?.let { menuNode.setMaterialInstance(it) }
    menuNode.renderableNodes.forEach {
      it.setPriority(PRIORITY_LAST)
      it.setBlendOrder(65_000)
      it.setCulling(false)
    }
    sceneView.childNodes = listOf(valuesNode, menuNode)
    sceneView.setOnTouchListener { _, event ->
      handleSceneTouch(event, sceneView)
      true
    }

    sceneView.cameraNode.position = Position(0f, 0f, 20f)
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
      menuNode.rotation = Rotation(menuToggleRotationDeg + menuTiltX, menuTiltY, 0f)
      applyMenuPartRotations(menuNode)
    }
  }

  private fun tuneAstralLighting(sceneView: SceneView) {
    // Match the web lighting ratios (hemi ~0.6, key ~1.0, fill ~0.35).
    sceneView.indirectLight?.intensity = 14_400f
    sceneView.mainLightNode?.apply {
      intensity = 24_000f
      color = Color(0.92f, 0.96f, 1f, 1f)
      lightDirection = Direction(x = 0.3f, y = -0.6f, z = -1f)
    }
  }

  private fun buildMenuMaterial(sceneView: SceneView): MaterialInstance {
    val key = MaterialProvider.MaterialKey().apply {
      doubleSided = false
      unlit = true
      // Use BLEND to mimic the web icon's transparent/basic material pipeline.
      alphaMode = 2
    }
    // Match the web material color (0xE9E6E0).
    val menuColor = Color(0.9137f, 0.9020f, 0.8784f, 1f)
    val instance = sceneView.materialLoader.createUbershaderInstance(
      config = key,
      label = "menu"
    ) ?: sceneView.materialLoader.createColorInstance(
      color = menuColor,
      metallic = 0.05f,
      roughness = 0.7f,
      reflectance = 0.4f
    )
    logMaterialParams(instance)
    instance.safeSetParameter("baseColorFactor", 0.9137f, 0.9020f, 0.8784f, 1f)
    instance.safeSetParameter("emissiveFactor", 0f, 0f, 0f)
    instance.safeSetParameter("metallicFactor", 0.05f)
    instance.safeSetParameter("roughnessFactor", 0.7f)
    return instance
  }

  private fun buildValuesMaterial(sceneView: SceneView, baseSize: Float): MaterialInstance {
    val key = MaterialProvider.MaterialKey().apply {
      doubleSided = false
      unlit = false
      // BLEND: match the translucent glass material used on astralpirates.com.
      alphaMode = 2
      hasTransmission = true
      hasClearCoat = true
      hasSheen = true
      hasIOR = true
    }

    val baseColor = hslToRgb(
      hue = 0f,
      saturation = valuesColorSaturation,
      lightness = valuesColorLightness
    )
    val targetThickness = kotlin.math.max(0.2f, baseSize * 0.35f)
    val tintDistance = kotlin.math.max(0.75f, baseSize * 0.9f) * valuesAttenuationDistanceMultiplier
    val attenuationColor = floatArrayOf(
      baseColor[0] * valuesAttenuationColorStrength,
      baseColor[1] * valuesAttenuationColorStrength,
      baseColor[2] * valuesAttenuationColorStrength,
    )

    val instance = sceneView.materialLoader.createUbershaderInstance(
      config = key,
      label = "values"
    ) ?: sceneView.materialLoader.createColorInstance(
      color = Color(baseColor[0], baseColor[1], baseColor[2], valuesAlpha),
      metallic = valuesMetallic,
      roughness = valuesRoughness,
      reflectance = valuesReflectance
    )

    logMaterialParams(instance)

    instance.safeSetParameter(
      "baseColorFactor",
      baseColor[0],
      baseColor[1],
      baseColor[2],
      valuesAlpha
    )
    instance.safeSetParameter("emissiveFactor", 0f, 0f, 0f)
    instance.safeSetParameter("metallicFactor", valuesMetallic)
    instance.safeSetParameter("roughnessFactor", valuesRoughness)
    instance.safeSetParameter("reflectance", valuesReflectance)
    instance.safeSetParameter("reflectivity", valuesReflectance)
    instance.safeSetParameter("reflectivityFactor", valuesReflectance)
    instance.safeSetParameter("transmissionFactor", 1f)
    instance.safeSetParameter("ior", valuesIor)
    instance.safeSetParameter(
      "sheenColorFactor",
      valuesSheenColor[0],
      valuesSheenColor[1],
      valuesSheenColor[2]
    )
    instance.safeSetParameter("sheenRoughnessFactor", valuesSheenRoughness)
    instance.safeSetParameter("clearCoatFactor", valuesClearCoat)
    instance.safeSetParameter("clearCoatRoughnessFactor", valuesClearCoatRoughness)
    instance.safeSetParameter("thicknessFactor", targetThickness)
    instance.safeSetParameter("attenuationDistance", tintDistance)
    instance.safeSetParameter("attenuationColor", attenuationColor[0], attenuationColor[1], attenuationColor[2])
    instance.safeSetParameter("envMapIntensity", valuesEnvMapIntensity)

    return instance
  }

  private fun updateValuesMaterial(dt: Float) {
    val material = valuesMaterial ?: return
    if (dt <= 0f) return

    val sheenSpeed = defaultSheenHueSpeed()
    val colorSpeed = defaultColorHueSpeed()

    sheenHue = (sheenHue + dt * sheenSpeed) % 1f
    val sheen = hslToRgb(sheenHue, 1f, 0.5f)

    colorHue = (colorHue + dt * colorSpeed) % 1f
    val base = hslToRgb(colorHue, valuesColorSaturation, valuesColorLightness)
    val attenuation = floatArrayOf(
      base[0] * valuesAttenuationColorStrength,
      base[1] * valuesAttenuationColorStrength,
      base[2] * valuesAttenuationColorStrength,
    )

    material.safeSetParameter("sheenColorFactor", sheen[0], sheen[1], sheen[2])
    // Some ubershader variants expose sheenColor rather than sheenColorFactor.
    material.safeSetParameter("sheenColor", sheen[0], sheen[1], sheen[2])
    material.safeSetParameter("baseColorFactor", base[0], base[1], base[2], valuesAlpha)
    material.safeSetParameter("attenuationColor", attenuation[0], attenuation[1], attenuation[2])
  }

  private fun defaultSheenHueSpeed(nowMs: Long = System.currentTimeMillis()): Float {
    val seconds = (nowMs % 60_000L) / 1000f
    val normalized = (seconds % 60f) / 60f
    val wave = triangleWave01(normalized)
    return sheenHueSpeedMin + (sheenHueSpeedMax - sheenHueSpeedMin) * wave
  }

  private fun defaultColorHueSpeed(nowMs: Long = System.currentTimeMillis()): Float {
    val minutes = (nowMs % 3_600_000L) / 60_000f
    val normalized = (minutes % 60f) / 60f
    val wave = triangleWave01(normalized)
    return colorHueSpeedMin + (colorHueSpeedMax - colorHueSpeedMin) * wave
  }

  private fun triangleWave01(normalized: Float): Float {
    val t = normalized % 1f
    return if (t <= 0.5f) t * 2f else (1f - t) * 2f
  }

  private fun startGyroscope() {
    val sensor = rotationVectorSensor ?: return
    if (gyroActive) return
    gyroActive = sensorManager.registerListener(
      gyroListener,
      sensor,
      SensorManager.SENSOR_DELAY_GAME
    )
  }

  private fun stopGyroscope() {
    if (!gyroActive) return
    sensorManager.unregisterListener(gyroListener)
    gyroActive = false
    gyroBaselinePitchDeg = null
    gyroBaselineRollDeg = null
  }

  private val gyroListener = object : SensorEventListener {
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    override fun onSensorChanged(event: SensorEvent) {
      if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
      SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

      val rotation = display?.rotation ?: Surface.ROTATION_0
      val (axisX, axisY) = when (rotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Z to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Z
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Z to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Z
      }
      SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
      SensorManager.getOrientation(remappedMatrix, orientation)

      val pitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
      val rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
      applyGyroInput(pitchDeg, rollDeg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
  }

  private fun applyGyroInput(pitchDeg: Float, rollDeg: Float) {
    val baselinePitch = gyroBaselinePitchDeg ?: pitchDeg.also { gyroBaselinePitchDeg = it }
    val baselineRoll = gyroBaselineRollDeg ?: rollDeg.also { gyroBaselineRollDeg = it }

    val normalizedGamma =
      ((rollDeg - baselineRoll) / gyroGammaRangeDeg).coerceIn(-1f, 1f)
    val normalizedBeta =
      ((pitchDeg - baselinePitch) / gyroBetaRangeDeg).coerceIn(-1f, 1f)

    // Web computes pointer in px then normalizes to [-1, 1], which doubles the ratio.
    val targetX = (normalizedGamma * gyroMovementRatioX * 2f).coerceIn(-1f, 1f)
    val targetY = (normalizedBeta * gyroMovementRatioY * 2f).coerceIn(-1f, 1f)

    gyroPointerX += (targetX - gyroPointerX) * gyroSmoothing
    gyroPointerY += (targetY - gyroPointerY) * gyroSmoothing

    inputState.targetX = gyroPointerX
    inputState.targetY = gyroPointerY
    inputState.lastInputMs = System.currentTimeMillis()

    gyroBaselinePitchDeg = baselinePitch + (pitchDeg - baselinePitch) * gyroBaselineRecenter
    gyroBaselineRollDeg = baselineRoll + (rollDeg - baselineRoll) * gyroBaselineRecenter
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
      cameraNear.toDouble(),
      cameraFar.toDouble()
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

  private fun updateMenuPlacement(sceneView: SceneView, menuNode: ModelNode) {
    val width = sceneView.width
    val height = sceneView.height
    if (width <= 0 || height <= 0 || menuBaseSize <= 0f) return
    val iconPx = iconSize(width.toFloat(), height.toFloat())
    val unitsPerPixelX = (orthoHalfWidth * 2f) / width
    val unitsPerPixelY = (orthoHalfHeight * 2f) / height
    val desiredWorldSize = iconPx * unitsPerPixelY
    val scale = (desiredWorldSize / menuBaseSize) * menuScaleMultiplier
    menuNode.scale = Scale(scale, scale, scale)

    val halfViewportW = width / 2f
    val halfViewportH = height / 2f
    val marginPx = iconPx * menuRelativeOffset
    val shiftPxX = kotlin.math.max(0f, halfViewportW - marginPx)
    val shiftPxY = kotlin.math.max(0f, halfViewportH - marginPx)
    val shiftWorldX = shiftPxX * unitsPerPixelX
    val shiftWorldY = shiftPxY * unitsPerPixelY

    val cameraPos = sceneView.cameraNode.position
    val cameraDistance = kotlin.math.sqrt(
      cameraPos.x * cameraPos.x + cameraPos.y * cameraPos.y + cameraPos.z * cameraPos.z
    ).coerceAtLeast(cameraNear * 2f)
    val forwardToCamera = normalize3(cameraPos.x, cameraPos.y, cameraPos.z)

    val worldUp = Float3(0f, 1f, 0f)
    var right = cross(worldUp, forwardToCamera)
    if (right.lengthSquared() <= 1e-6f) {
      right = Float3(1f, 0f, 0f)
    }
    right = right.normalized()
    val up = cross(forwardToCamera, right).normalized()

    val placementX = right.x * shiftWorldX + up.x * shiftWorldY
    val placementY = right.y * shiftWorldX + up.y * shiftWorldY
    val placementZ = right.z * shiftWorldX + up.z * shiftWorldY

    val margin = kotlin.math.max(cameraNear * 2f, kotlin.math.abs(menuDepthOffset))
    val maxDistance = cameraDistance - cameraNear * 1.1f
    val upperBound = kotlin.math.max(cameraNear, maxDistance)
    val forwardDistance = (cameraDistance - margin).coerceIn(cameraNear, upperBound)

    menuNode.position = Position(
      menuBaseOffset.x + placementX + forwardToCamera.x * forwardDistance,
      menuBaseOffset.y + placementY + forwardToCamera.y * forwardDistance,
      menuBaseOffset.z + placementZ + forwardToCamera.z * forwardDistance,
    )
  }

  private fun computeRenderableBounds(node: ModelNode): Pair<Float, Float3> {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    var found = false

    node.renderableNodes.forEach { renderable ->
      val box: Box = renderable.axisAlignedBoundingBox
      val cx = box.center[0]
      val cy = box.center[1]
      val cz = box.center[2]
      val hx = box.halfExtent[0]
      val hy = box.halfExtent[1]
      val hz = box.halfExtent[2]
      if (!cx.isFinite() || !cy.isFinite() || !cz.isFinite()) return@forEach
      if (!hx.isFinite() || !hy.isFinite() || !hz.isFinite()) return@forEach

      minX = kotlin.math.min(minX, cx - hx)
      minY = kotlin.math.min(minY, cy - hy)
      minZ = kotlin.math.min(minZ, cz - hz)
      maxX = kotlin.math.max(maxX, cx + hx)
      maxY = kotlin.math.max(maxY, cy + hy)
      maxZ = kotlin.math.max(maxZ, cz + hz)
      found = true
    }

    if (!found) {
      val fallbackCenter = node.center
      return maxExtent(node) to Float3(fallbackCenter.x, fallbackCenter.y, fallbackCenter.z)
    }

    val sizeX = maxX - minX
    val sizeY = maxY - minY
    val sizeZ = maxZ - minZ
    val baseSize = kotlin.math.max(sizeX, kotlin.math.max(sizeY, sizeZ)).coerceAtLeast(1e-4f)
    val center = Float3(
      x = (minX + maxX) * 0.5f,
      y = (minY + maxY) * 0.5f,
      z = (minZ + maxZ) * 0.5f,
    )
    return baseSize to center
  }

  private fun assignMenuParts(menuNode: ModelNode) {
    val candidates = menuNode.renderableNodes.toMutableList()
    if (candidates.isEmpty()) {
      menuPartTop = null
      menuPartCenter = null
      menuPartBottom = null
      menuPartRotationDeg.clear()
      menuPartAnimators.values.forEach { it.cancel() }
      menuPartAnimators.clear()
      return
    }

    candidates.sortWith { a, b ->
      val ay = a.axisAlignedBoundingBox.center[1] + menuBaseOffset.y
      val by = b.axisAlignedBoundingBox.center[1] + menuBaseOffset.y
      by.compareTo(ay)
    }

    val lastIndex = candidates.lastIndex
    val midIndex = lastIndex / 2
    menuPartTop = candidates.firstOrNull()
    menuPartCenter = candidates.getOrNull(midIndex)
    menuPartBottom = candidates.getOrNull(lastIndex)

    val activeParts = listOfNotNull(menuPartTop, menuPartCenter, menuPartBottom).toSet()
    menuPartAnimators.keys.filterNot { it in activeParts }.forEach { part ->
      menuPartAnimators.remove(part)?.cancel()
      menuPartRotationDeg.remove(part)
      menuPartBaseRotation.remove(part)
    }
    activeParts.forEach { part ->
      if (!menuPartBaseRotation.containsKey(part)) {
        menuPartBaseRotation[part] = part.rotation
      }
      if (!menuPartRotationDeg.containsKey(part)) {
        menuPartRotationDeg[part] = 0f
      }
    }
  }

  private fun applyMenuPartRotations(menuNode: ModelNode) {
    menuNode.renderableNodes.forEach { part ->
      val rotX = menuPartRotationDeg[part] ?: 0f
      val base = menuPartBaseRotation[part] ?: Rotation(0f, 0f, 0f)
      part.rotation = Rotation(base.x + rotX, base.y, base.z)
    }
  }

  private fun handleSceneTouch(event: MotionEvent, sceneView: SceneView) {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        menuTapDownX = event.x
        menuTapDownY = event.y
        menuTapDownTimeMs = SystemClock.uptimeMillis()
      }
      MotionEvent.ACTION_UP -> {
        val elapsed = SystemClock.uptimeMillis() - menuTapDownTimeMs
        val dx = event.x - menuTapDownX
        val dy = event.y - menuTapDownY
        val slop = menuTapSlopPx.coerceAtLeast(1f)
        val isTap = elapsed in 0..menuTapTimeoutMs && (dx * dx + dy * dy) <= slop * slop
        if (isTap && isWithinMenuHitArea(event.x, event.y, sceneView)) {
          triggerMenuToggleAnimation()
        }
        menuTapDownTimeMs = 0L
      }
      MotionEvent.ACTION_CANCEL -> {
        menuTapDownTimeMs = 0L
      }
    }
  }

  private fun isWithinMenuHitArea(x: Float, y: Float, sceneView: SceneView): Boolean {
    val width = sceneView.width
    val height = sceneView.height
    if (width <= 0 || height <= 0) return false
    val iconPx = iconSize(width.toFloat(), height.toFloat())
    if (!iconPx.isFinite() || iconPx <= 0f) return false

    val halfViewportW = width / 2f
    val halfViewportH = height / 2f
    val marginPx = iconPx * menuRelativeOffset
    val shiftPxX = kotlin.math.max(0f, halfViewportW - marginPx)
    val shiftPxY = kotlin.math.max(0f, halfViewportH - marginPx)
    val menuPx = halfViewportW + shiftPxX
    val menuPy = halfViewportH - shiftPxY

    val dx = x - menuPx
    val dy = y - menuPy
    return dx * dx + dy * dy <= iconPx * iconPx
  }

  private fun triggerMenuToggleAnimation(direction: Int? = null) {
    fun resolveSign(value: Int?): Int {
      return when (value) {
        1, -1 -> value
        else -> if (Math.random() < 0.5f) -1 else 1
      }
    }

    menuRotationAnimator?.cancel()
    val groupSign = resolveSign(direction)
    val start = menuToggleRotationDeg
    val target = start + groupSign * 90f
    menuRotationAnimator = ValueAnimator.ofFloat(start, target).apply {
      duration = 600L
      interpolator = DecelerateInterpolator()
      addUpdateListener { animator ->
        val value = animator.animatedValue as Float
        menuToggleRotationDeg = normalizeDegrees(value)
      }
      start()
    }

    listOfNotNull(menuPartTop, menuPartCenter, menuPartBottom).forEach { part ->
      val partSign = resolveSign(null)
      tweenMenuPartRotation(part, partSign * 90f)
    }
  }

  private fun tweenMenuPartRotation(part: ModelNode.RenderableNode, deltaDegrees: Float) {
    menuPartAnimators.remove(part)?.cancel()
    val start = menuPartRotationDeg[part] ?: 0f
    val target = start + deltaDegrees
    val animator = ValueAnimator.ofFloat(start, target).apply {
      duration = menuPartAnimationDurationMs
      interpolator = DecelerateInterpolator()
      addUpdateListener { valueAnimator ->
        val value = valueAnimator.animatedValue as Float
        menuPartRotationDeg[part] = normalizeDegrees(value)
      }
    }
    animator.start()
    menuPartAnimators[part] = animator
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

private data class Float3(
  val x: Float,
  val y: Float,
  val z: Float,
) {
  fun lengthSquared(): Float = x * x + y * y + z * z

  fun normalized(): Float3 {
    val length = kotlin.math.sqrt(lengthSquared())
    if (length <= 0f) return this
    return Float3(x / length, y / length, z / length)
  }
}

private fun normalize3(x: Float, y: Float, z: Float): Float3 {
  val vec = Float3(x, y, z)
  val length = kotlin.math.sqrt(vec.lengthSquared())
  if (length <= 0f) return Float3(0f, 0f, 0f)
  return Float3(x / length, y / length, z / length)
}

private fun cross(a: Float3, b: Float3): Float3 {
  return Float3(
    a.y * b.z - a.z * b.y,
    a.z * b.x - a.x * b.z,
    a.x * b.y - a.y * b.x,
  )
}

private fun normalizeDegrees(value: Float): Float {
  var normalized = value % 360f
  if (normalized > 180f) normalized -= 360f
  if (normalized < -180f) normalized += 360f
  return normalized
}

private fun hslToRgb(hue: Float, saturation: Float, lightness: Float): FloatArray {
  if (saturation <= 0f) {
    return floatArrayOf(lightness, lightness, lightness)
  }

  val h = ((hue % 1f) + 1f) % 1f
  val q = if (lightness < 0.5f) {
    lightness * (1f + saturation)
  } else {
    lightness + saturation - lightness * saturation
  }
  val p = 2f * lightness - q

  fun hueToChannel(tRaw: Float): Float {
    var t = tRaw
    if (t < 0f) t += 1f
    if (t > 1f) t -= 1f
    return when {
      t < 1f / 6f -> p + (q - p) * 6f * t
      t < 1f / 2f -> q
      t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
      else -> p
    }
  }

  val r = hueToChannel(h + 1f / 3f)
  val g = hueToChannel(h)
  val b = hueToChannel(h - 1f / 3f)
  return floatArrayOf(r, g, b)
}

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
