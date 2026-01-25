package com.example.offlinevoice.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import com.example.offlinevoice.R
import kotlin.math.max

class AnimatedGradientTextView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

  private val gradientColors = intArrayOf(
    0xFFFF0040.toInt(),
    0xFFFF8000.toInt(),
    0xFFFFD400.toInt(),
    0xFF22DD00.toInt(),
    0xFF00C0FF.toInt(),
    0xFF6A00FF.toInt(),
    0xFFFF00C8.toInt(),
    0xFFFF0040.toInt(),
  )

  private val gradientPositions = floatArrayOf(
    0f, 0.15f, 0.28f, 0.42f, 0.56f, 0.7f, 0.85f, 1f,
  )

  private val gradientMatrix = Matrix()
  private var gradientShader: LinearGradient? = null
  private var gradientTravel = 0f
  private var reverse = false
  private var durationMs = 12000L
  private var animator: ValueAnimator? = null

  init {
    context.obtainStyledAttributes(attrs, R.styleable.AnimatedGradientTextView).apply {
      reverse = getBoolean(R.styleable.AnimatedGradientTextView_gradientReverse, false)
      durationMs = getInt(R.styleable.AnimatedGradientTextView_gradientDuration, 12000).toLong()
      recycle()
    }
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (w > 0) {
      createGradient(w)
      updateGradient(0.5f)
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    maybeStartAnimator()
  }

  override fun onDetachedFromWindow() {
    stopAnimator()
    super.onDetachedFromWindow()
  }

  override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
    super.onVisibilityChanged(changedView, visibility)
    if (visibility == VISIBLE) {
      maybeStartAnimator()
    } else {
      stopAnimator()
    }
  }

  private fun createGradient(width: Int) {
    val span = max(width.toFloat(), 1f)
    gradientTravel = span
    gradientShader = LinearGradient(
      -span,
      0f,
      span,
      0f,
      gradientColors,
      gradientPositions,
      Shader.TileMode.CLAMP,
    )
    paint.shader = gradientShader
  }

  private fun updateGradient(fraction: Float) {
    val shader = gradientShader ?: return
    val travel = gradientTravel * 2f
    val offset = travel * fraction
    val translate = if (reverse) -offset else offset
    gradientMatrix.setTranslate(translate, 0f)
    shader.setLocalMatrix(gradientMatrix)
    invalidate()
  }

  private fun maybeStartAnimator() {
    if (width == 0 || height == 0) return
    if (!ValueAnimator.areAnimatorsEnabled()) {
      updateGradient(0.5f)
      return
    }

    if (animator == null) {
      animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = durationMs
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { updateGradient(it.animatedFraction) }
      }
    }

    if (animator?.isStarted == true) return
    animator?.start()
  }

  private fun stopAnimator() {
    animator?.cancel()
  }
}
