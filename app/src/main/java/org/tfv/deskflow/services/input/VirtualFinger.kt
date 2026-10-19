/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */


package org.tfv.deskflow.services.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import kotlin.math.hypot

class VirtualFinger(
  private val service: AccessibilityService,
  private val handler: Handler,
  private val boundsProvider: () -> PointF,
) {

  var isDown = false
    private set

  val isLifting: Boolean
    get() = isDown && upRequested

  val position: PointF
    get() = PointF(target.x, target.y)

  var minSegmentMs: Long = MIN_SEGMENT_MS

  var onLifted: (() -> Unit)? = null

  private val lastPoint = PointF()

  private val target = PointF()

  private var stroke: StrokeDescription? = null

  private var inFlight = false

  private var upRequested = false

  private var generation = 0

  fun down(x: Float, y: Float) {
    if (isDown) cancel()
    generation++

    val p = clamp(x, y)
    lastPoint.set(p)
    target.set(p)
    stroke = null
    upRequested = false
    isDown = true
  }

  fun hold() {
    if (!isDown || stroke != null || inFlight) return
    dispatchSegment(nudge(lastPoint), HOLD_SEGMENT_MS, willContinue = true)
  }

  fun moveTo(x: Float, y: Float) {
    if (!isDown || upRequested) return
    target.set(clamp(x, y))
    pump()
  }

  fun up() {
    if (!isDown) return
    upRequested = true
    pump()
  }

  fun cancel() {
    val wasDown = isDown
    isDown = false
    upRequested = false
    stroke = null
    if (wasDown) onLifted?.invoke()
  }

  private fun pump() {
    if (!isDown || inFlight) return

    val distance = hypot(target.x - lastPoint.x, target.y - lastPoint.y)
    when {
      distance >= 1f -> {
        val duration =
          (distance * MS_PER_PX)
            .toLong()
            .coerceIn(minSegmentMs, maxOf(MAX_SEGMENT_MS, minSegmentMs))
        dispatchSegment(PointF(target.x, target.y), duration, willContinue = true)
      }

      upRequested ->
        if (stroke == null) {
          cancel()
        } else {
          dispatchSegment(nudge(lastPoint), LIFT_SEGMENT_MS, willContinue = false)
        }
    }
  }

  private fun dispatchSegment(to: PointF, duration: Long, willContinue: Boolean) {
    val path =
      Path().apply {
        moveTo(lastPoint.x, lastPoint.y)
        lineTo(to.x, to.y)
      }
    val next =
      stroke?.continueStroke(path, 0, duration, willContinue)
        ?: StrokeDescription(path, 0, duration, willContinue)

    stroke = next
    lastPoint.set(to)
    inFlight = true

    val gen = generation
    val dispatched =
      service.dispatchGesture(
        GestureDescription.Builder().addStroke(next).build(),
        object : AccessibilityService.GestureResultCallback() {
          override fun onCompleted(gestureDescription: GestureDescription) {
            inFlight = false
            if (gen == generation && stroke?.willContinue() == false) cancel() else pump()
          }

          override fun onCancelled(gestureDescription: GestureDescription) {
            inFlight = false
            if (gen == generation) cancel() else pump()
          }
        },
        handler,
      )
    if (!dispatched) {
      inFlight = false
      cancel()
    }
  }

  private fun nudge(p: PointF): PointF {
    val bounds = boundsProvider()
    return PointF(if (p.x + 1f < bounds.x) p.x + 1f else p.x - 1f, p.y)
  }

  private fun clamp(x: Float, y: Float): PointF {
    val bounds = boundsProvider()
    return PointF(
      x.coerceIn(1f, (bounds.x - 2f).coerceAtLeast(1f)),
      y.coerceIn(1f, (bounds.y - 2f).coerceAtLeast(1f)),
    )
  }

  companion object {
    private const val MS_PER_PX = 0.3f
    private const val MIN_SEGMENT_MS = 32L
    private const val MAX_SEGMENT_MS = 90L
    private const val HOLD_SEGMENT_MS = 40L
    private const val LIFT_SEGMENT_MS = 120L
  }
}
