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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

enum class ScrollMode(val id: Int) {
  Finger(0),
  Swipes(1),
  Flick(2),
  Steps(3),
  Page(4);

  companion object {
    fun fromId(id: Int): ScrollMode = entries.find { it.id == id } ?: Finger
  }
}

class ScrollController(
  private val service: AccessibilityService,
  private val finger: VirtualFinger,
  private val handler: Handler,
  private val pointerProvider: () -> PointF,
  private val boundsProvider: () -> PointF,
  private val density: Float,
  private val nodeScroller: (x: Float, y: Float, dirX: Int, dirY: Int) -> Unit,
) {

  @Volatile var invertY: Boolean = false

  @Volatile var invertX: Boolean = false

  @Volatile var speed: Float = 1f

  @Volatile var smoothing: Float = DEFAULT_SMOOTHING

  @Volatile var mode: ScrollMode = ScrollMode.Finger

  private val pending = PointF()

  private enum class Axis {
    None,
    X,
    Y,
  }

  private var lockedAxis = Axis.None

  private var lastWheelAt = 0L

  private var ownsFinger = false

  private var swipeInFlight = false

  private var flushScheduled = false

  private var lastPageScrollAt = 0L

  private val idleMs: Long
    get() = (120 + smoothing * 150).toLong()

  private val swipeWindowMs: Long
    get() = (30 + smoothing * 170).toLong()

  private val idleRunnable = Runnable { onIdle() }

  private val flushRunnable = Runnable { flushSwipe() }

  fun onWheel(xDelta: Int, yDelta: Int) {
    val notchPx = NOTCH_DP * density * speed
    val directionX = if (invertX) -1f else 1f
    val directionY = if (invertY) -1f else 1f
    lastWheelAt = System.currentTimeMillis()

    var dx = -xDelta / WHEEL_DELTA_PER_NOTCH * notchPx * directionX
    var dy = yDelta / WHEEL_DELTA_PER_NOTCH * notchPx * directionY

    if (lockedAxis == Axis.None && (dx != 0f || dy != 0f)) {
      lockedAxis = if (abs(dy) >= abs(dx)) Axis.Y else Axis.X
    }
    when (lockedAxis) {
      Axis.Y -> dx = 0f
      Axis.X -> dy = 0f
      Axis.None -> {}
    }

    val bounds = boundsProvider()
    val maxX = bounds.x * MAX_PENDING_SCREEN_FRACTION
    val maxY = bounds.y * MAX_PENDING_SCREEN_FRACTION
    pending.set(
      (pending.x + dx).coerceIn(-maxX, maxX),
      (pending.y + dy).coerceIn(-maxY, maxY),
    )

    handler.removeCallbacks(idleRunnable)
    handler.postDelayed(idleRunnable, idleMs)

    when (mode) {
      ScrollMode.Finger -> applyFinger()
      ScrollMode.Swipes -> scheduleSwipe()
      ScrollMode.Flick ->
        if (hypot(pending.x, pending.y) > bounds.y * FLICK_EARLY_SCREEN_FRACTION) {
          flick()
        }
      ScrollMode.Steps -> applySteps()
      ScrollMode.Page -> applyPage()
    }
  }

  fun reset() {
    handler.removeCallbacks(idleRunnable)
    clearPending()
    if (ownsFinger) {
      ownsFinger = false
      finger.cancel()
    }
  }

  private fun onIdle() {
    if (mode == ScrollMode.Flick) flick()
    clearPending()
    if (ownsFinger && finger.isDown) finger.up()
  }

  private fun clearPending() {
    handler.removeCallbacks(flushRunnable)
    flushScheduled = false
    pending.set(0f, 0f)
    lockedAxis = Axis.None
  }

  private fun hasPending() = pending.x != 0f || pending.y != 0f

  private fun applyFinger() {
    if (!hasPending() || !takeFinger()) return

    val bounds = boundsProvider()
    val current = finger.position
    val wantedX = current.x + pending.x
    val wantedY = current.y + pending.y

    val minX = minOf(bounds.x * EDGE_FRACTION, current.x)
    val maxX = maxOf(bounds.x * (1f - EDGE_FRACTION), current.x)
    val minY = minOf(bounds.y * EDGE_FRACTION, current.y)
    val maxY = maxOf(bounds.y * (1f - EDGE_FRACTION), current.y)

    val x = wantedX.coerceIn(minX, maxX)
    val y = wantedY.coerceIn(minY, maxY)

    if (x == current.x && y == current.y) {
      pending.set(0f, 0f)
      finger.up()
      return
    }

    pending.set(wantedX - x, wantedY - y)
    finger.minSegmentMs = (16 + smoothing * 84).toLong()
    finger.moveTo(x, y)

    if (hasPending()) finger.up()
  }

  private fun takeFinger(): Boolean {
    if (ownsFinger && finger.isLifting) return false

    if (!finger.isDown) {
      val pointer = pointerProvider()
      ownsFinger = true
      finger.onLifted = {
        ownsFinger = false
        if (System.currentTimeMillis() - lastWheelAt >= idleMs) {
          pending.set(0f, 0f)
        } else if (hasPending()) {
          handler.post {
            when (mode) {
              ScrollMode.Finger -> applyFinger()
              ScrollMode.Steps -> applySteps()
              else -> {}
            }
          }
        }
      }
      finger.down(pointer.x, pointer.y)
      return true
    }

    if (!ownsFinger) {
      pending.set(0f, 0f)
      return false
    }
    return true
  }

  private fun scheduleSwipe() {
    if (swipeInFlight || flushScheduled) return
    flushScheduled = true
    handler.postDelayed(flushRunnable, swipeWindowMs)
  }

  private fun flushSwipe() {
    flushScheduled = false
    if (swipeInFlight || !hasPending()) return
    dispatchSwipe(takePending(), (swipeWindowMs * 1.5f).toLong().coerceAtLeast(60L)) {
      if (hasPending()) scheduleSwipe()
    }
  }

  private fun flick() {
    if (swipeInFlight || !hasPending()) return
    dispatchSwipe(takePending(), (80 + smoothing * 120).toLong())
  }

  private fun takePending(): PointF = PointF(pending.x, pending.y).also { pending.set(0f, 0f) }

  private fun applySteps() {
    if (hypot(pending.x, pending.y) < STEP_THRESHOLD_DP * density) return
    if (ownsFinger && finger.isDown) return
    if (!takeFinger()) return

    val step = boundsProvider().y * STEP_SCREEN_FRACTION
    val direction = takePending()
    val start = finger.position
    finger.moveTo(start.x + sign(direction.x) * step, start.y + sign(direction.y) * step)
    finger.up()
  }

  private fun applyPage() {
    if (hypot(pending.x, pending.y) < PAGE_THRESHOLD_DP * density) return

    val direction = takePending()
    val now = System.currentTimeMillis()
    if (now - lastPageScrollAt < (250 + smoothing * 350).toLong()) return
    lastPageScrollAt = now

    val pointer = pointerProvider()
    nodeScroller(pointer.x, pointer.y, sign(direction.x).toInt(), sign(direction.y).toInt())
  }

  private fun dispatchSwipe(distance: PointF, durationMs: Long, onDone: () -> Unit = {}) {
    val bounds = boundsProvider()
    val pointer = pointerProvider()
    val startX = pointer.x.coerceIn(SWIPE_MARGIN, bounds.x - SWIPE_MARGIN)
    val startY = pointer.y.coerceIn(SWIPE_MARGIN, bounds.y - SWIPE_MARGIN)
    val endX = (startX + distance.x).coerceIn(SWIPE_MARGIN, bounds.x - SWIPE_MARGIN)
    val endY = (startY + distance.y).coerceIn(SWIPE_MARGIN, bounds.y - SWIPE_MARGIN)
    if (hypot(endX - startX, endY - startY) < 1f) return

    val path =
      Path().apply {
        moveTo(startX, startY)
        lineTo(endX, endY)
      }
    swipeInFlight =
      service.dispatchGesture(
        GestureDescription.Builder().addStroke(StrokeDescription(path, 0, durationMs)).build(),
        object : AccessibilityService.GestureResultCallback() {
          override fun onCompleted(gestureDescription: GestureDescription) {
            swipeInFlight = false
            onDone()
          }

          override fun onCancelled(gestureDescription: GestureDescription) {
            swipeInFlight = false
          }
        },
        handler,
      )
  }

  companion object {
    const val DEFAULT_SMOOTHING = 0.4f

    private const val WHEEL_DELTA_PER_NOTCH = 120f
    private const val NOTCH_DP = 16f
    private const val MAX_PENDING_SCREEN_FRACTION = 0.3f
    private const val EDGE_FRACTION = 0.05f
    private const val FLICK_EARLY_SCREEN_FRACTION = 0.25f
    private const val STEP_THRESHOLD_DP = 40f
    private const val STEP_SCREEN_FRACTION = 0.12f
    private const val PAGE_THRESHOLD_DP = 60f
    private const val SWIPE_MARGIN = 2f
  }
}
