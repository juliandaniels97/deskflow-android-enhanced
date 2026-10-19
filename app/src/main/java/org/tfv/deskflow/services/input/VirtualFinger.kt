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
import org.tfv.deskflow.client.util.logging.KLoggingManager

/**
 * A single virtual finger that can be put down, moved and lifted using
 * continued accessibility gestures.
 *
 * Unlike a one-shot swipe, the finger stays on the screen between moves, so
 * content follows the mouse (drag) or the scroll wheel 1:1. When lifted, the
 * finger slows down to (almost) zero velocity first, which prevents
 * unintended flings.
 *
 * All methods must be called on the thread of [handler].
 */
class VirtualFinger(
  private val service: AccessibilityService,
  private val handler: Handler,
  private val boundsProvider: () -> PointF,
) {

  /** `true` while the finger is (or is about to be) on the screen. */
  var isDown = false
    private set

  /** `true` once [up] was requested & the finger is not yet fully lifted. */
  val isLifting: Boolean
    get() = isDown && upRequested

  /** Position of the end of the last dispatched segment. */
  private val lastPoint = PointF()

  /** Requested position of the finger. */
  private val target = PointF()

  /** The last dispatched stroke, used for continuation. */
  private var stroke: StrokeDescription? = null

  private var inFlight = false

  private var upRequested = false

  /** Invoked once the finger has been fully lifted (or was cancelled). */
  var onLifted: (() -> Unit)? = null

  /** Incremented on every [down], so stale gesture callbacks are ignored. */
  private var generation = 0

  private fun createCallback(gen: Int) =
    object : AccessibilityService.GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        inFlight = false
        when {
          gen != generation -> pump()
          stroke?.willContinue() == false -> reset()
          else -> pump()
        }
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        log.debug { "Virtual finger gesture cancelled" }
        inFlight = false
        if (gen == generation) reset() else pump()
      }
    }

  /** Put the finger down at [x], [y]. */
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

  /**
   * Keep the finger on the screen without moving it (e.g. to trigger a long
   * press). A minimal movement is dispatched so the touch-down happens now.
   */
  fun hold() {
    if (!isDown || stroke != null || inFlight) return
    dispatchSegment(nudge(lastPoint), HOLD_SEGMENT_MS, willContinue = true)
  }

  /** Move the finger to [x], [y]. */
  fun moveTo(x: Float, y: Float) {
    if (!isDown || upRequested) return
    target.set(clamp(x, y))
    pump()
  }

  /** Move the finger by [dx], [dy] relative to its requested position. */
  fun moveBy(dx: Float, dy: Float) = moveTo(target.x + dx, target.y + dy)

  /** Current requested position. */
  val position: PointF
    get() = PointF(target.x, target.y)

  /** Lift the finger, after slowing down to avoid a fling. */
  fun up() {
    if (!isDown) return
    upRequested = true
    pump()
  }

  /** Drop all state without lifting gracefully. */
  fun cancel() {
    reset()
  }

  private fun reset() {
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
        // Move towards the target, finger stays down.
        val duration =
          (distance * MS_PER_PX).toLong().coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
        dispatchSegment(PointF(target.x, target.y), duration, willContinue = true)
      }

      upRequested -> {
        if (stroke == null) {
          // Never moved, nothing was dispatched, nothing to lift
          reset()
          return
        }
        // Final, very slow tiny movement -> velocity ~0 -> no fling.
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
      try {
        stroke?.continueStroke(path, 0, duration, willContinue)
          ?: StrokeDescription(path, 0, duration, willContinue)
      } catch (err: Exception) {
        log.error(err) { "Unable to create stroke" }
        reset()
        return
      }

    stroke = next
    lastPoint.set(to)
    inFlight = true

    val dispatched =
      service.dispatchGesture(
        GestureDescription.Builder().addStroke(next).build(),
        createCallback(generation),
        handler,
      )
    if (!dispatched) {
      log.warn { "dispatchGesture rejected the virtual finger stroke" }
      inFlight = false
      reset()
    }
  }

  /** A point 1px away from [p], staying inside the screen. */
  private fun nudge(p: PointF): PointF {
    val bounds = boundsProvider()
    val nx = if (p.x + 1f < bounds.x) p.x + 1f else p.x - 1f
    return PointF(nx, p.y)
  }

  private fun clamp(x: Float, y: Float): PointF {
    val bounds = boundsProvider()
    return PointF(
      x.coerceIn(1f, (bounds.x - 2f).coerceAtLeast(1f)),
      y.coerceIn(1f, (bounds.y - 2f).coerceAtLeast(1f)),
    )
  }

  companion object {
    private val log = KLoggingManager.logger(VirtualFinger::class.java.simpleName)

    private const val MS_PER_PX = 0.4f
    private const val MIN_SEGMENT_MS = 16L
    private const val MAX_SEGMENT_MS = 120L
    private const val HOLD_SEGMENT_MS = 40L
    private const val LIFT_SEGMENT_MS = 120L
  }
}
