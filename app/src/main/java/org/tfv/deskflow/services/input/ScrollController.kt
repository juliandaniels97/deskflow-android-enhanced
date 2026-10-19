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
import org.tfv.deskflow.client.util.logging.KLoggingManager

/** The available ways to turn mouse wheel / trackpad scrolling into touch. */
enum class ScrollMode(val id: Int) {
  /** A finger stays on the screen and follows the wheel 1:1. */
  Finger(0),

  /** Wheel events are collected briefly, then applied as a short swipe. */
  Swipes(1),

  /** One swipe with momentum per scroll movement, like a touch flick. */
  Flick(2),

  /** Fixed, slow steps without momentum. */
  Steps(3),

  /** No gestures: asks the scrollable view under the pointer to scroll. */
  Page(4);

  companion object {
    fun fromId(id: Int): ScrollMode = entries.find { it.id == id } ?: Finger
  }
}

/**
 * Translates Deskflow mouse wheel events into touch scrolling, using the
 * selected [mode].
 *
 * Common to all modes: the direction can be inverted per axis, each scroll
 * movement is locked to its dominant axis (trackpads always send some noise
 * on the other axis) and nothing keeps scrolling once the wheel is idle.
 */
class ScrollController(
  private val service: AccessibilityService,
  private val finger: VirtualFinger,
  private val handler: Handler,
  private val pointerProvider: () -> PointF,
  private val boundsProvider: () -> PointF,
  /** Display density, to scroll the same physical distance on all devices */
  private val density: Float,
  /**
   * Scrolls the scrollable view at [x], [y] by one step (accessibility
   * action). [dirX]/[dirY] are the finger directions (-1, 0, 1).
   */
  private val nodeScroller: (x: Float, y: Float, dirX: Int, dirY: Int) -> Boolean,
) {

  /** Invert the vertical scroll direction. */
  @Volatile var invertY: Boolean = false

  /** Invert the horizontal scroll direction. */
  @Volatile var invertX: Boolean = false

  /** Scroll speed multiplier, `1.0` = default. */
  @Volatile var speed: Float = 1f

  /** Smoothing `0..1`: higher = smoother, but more latency. */
  @Volatile var smoothing: Float = DEFAULT_SMOOTHING
    set(value) {
      field = value.coerceIn(0f, 1f)
      finger.minSegmentMs = (16 + field * 84).toLong()
    }

  @Volatile var mode: ScrollMode = ScrollMode.Finger

  /** Scroll distance (finger movement in px) not yet applied. */
  private val pending = PointF()

  private enum class Axis {
    None,
    X,
    Y,
  }

  /** Axis the current scroll movement (burst of wheel events) is locked to */
  private var lockedAxis = Axis.None

  /** Time of the last wheel event */
  private var lastWheelAt = 0L

  /** `true` while the finger is used by this controller ([ScrollMode.Finger] & [ScrollMode.Steps]) */
  private var ownsFinger = false

  /** `true` while a one-shot swipe of this controller is running */
  private var swipeInFlight = false

  private var lastPageScrollAt = 0L

  private val idleMs: Long
    get() = (120 + smoothing * 150).toLong()

  /** The scroll movement ended (no more wheel events) */
  private val idleRunnable = Runnable { onIdle() }

  private val flushRunnable = Runnable { flushSwipe() }

  init {
    smoothing = DEFAULT_SMOOTHING
  }

  /**
   * Handle a wheel event. Deskflow uses 120 units per wheel notch; macOS
   * sends every trackpad event as (accelerated) lines.
   */
  fun onWheel(xDelta: Int, yDelta: Int) {
    val notchPx = NOTCH_DP * density * speed
    val directionX = if (invertX) -1f else 1f
    val directionY = if (invertY) -1f else 1f
    lastWheelAt = System.currentTimeMillis()

    // Wheel up (positive y) shows content above -> finger moves down.
    // Wheel right (positive x) shows content on the right -> finger moves left.
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

    pending.x += dx
    pending.y += dy

    // Never build up a backlog
    val bounds = boundsProvider()
    val maxX = bounds.x * MAX_PENDING_SCREEN_FRACTION
    val maxY = bounds.y * MAX_PENDING_SCREEN_FRACTION
    pending.set(pending.x.coerceIn(-maxX, maxX), pending.y.coerceIn(-maxY, maxY))

    handler.removeCallbacks(idleRunnable)
    handler.postDelayed(idleRunnable, idleMs)

    when (mode) {
      ScrollMode.Finger -> applyFinger()
      ScrollMode.Swipes -> scheduleSwipe()
      ScrollMode.Flick -> {
        // Flick early if the movement is long, otherwise at the end
        if (hypot(pending.x, pending.y) > bounds.y * FLICK_EARLY_SCREEN_FRACTION) {
          flick()
        }
      }
      ScrollMode.Steps -> applySteps()
      ScrollMode.Page -> applyPage()
    }
  }

  /** Abort scrolling, e.g. when a mouse button is pressed. */
  fun reset() {
    handler.removeCallbacks(idleRunnable)
    handler.removeCallbacks(flushRunnable)
    flushScheduled = false
    pending.set(0f, 0f)
    lockedAxis = Axis.None
    if (ownsFinger) {
      ownsFinger = false
      finger.cancel()
    }
  }

  private fun onIdle() {
    if (mode == ScrollMode.Flick) flick()
    // Nothing must keep scrolling after the wheel stopped
    pending.set(0f, 0f)
    lockedAxis = Axis.None
    handler.removeCallbacks(flushRunnable)
    flushScheduled = false
    if (ownsFinger && finger.isDown) finger.up()
  }

  // ---------------------------------------------------------------------------
  // Mode: Finger (continuous)
  // ---------------------------------------------------------------------------

  private fun applyFinger() {
    if (pending.x == 0f && pending.y == 0f) return
    if (!takeFinger()) return

    val bounds = boundsProvider()
    val current = finger.position
    val wantedX = current.x + pending.x
    val wantedY = current.y + pending.y

    // Never force the finger in the wrong direction, if the pointer itself
    // is already inside the edge area.
    val minX = minOf(bounds.x * EDGE_FRACTION, current.x)
    val maxX = maxOf(bounds.x * (1f - EDGE_FRACTION), current.x)
    val minY = minOf(bounds.y * EDGE_FRACTION, current.y)
    val maxY = maxOf(bounds.y * (1f - EDGE_FRACTION), current.y)

    val x = wantedX.coerceIn(minX, maxX)
    val y = wantedY.coerceIn(minY, maxY)

    if (x == current.x && y == current.y) {
      // No room at all in the scroll direction, drop the distance
      pending.set(0f, 0f)
      finger.up()
      return
    }

    pending.set(wantedX - x, wantedY - y)
    finger.moveTo(x, y)

    // Reached an edge, lift and restart at the pointer with the remainder.
    if (pending.x != 0f || pending.y != 0f) {
      finger.up()
    }
  }

  /**
   * Ensure the finger is down at the pointer & owned by this controller.
   * @return `false` if the finger can't be used right now
   */
  private fun takeFinger(): Boolean {
    if (ownsFinger && finger.isLifting) {
      // Wait until the finger was lifted, then continue at the pointer
      return false
    }

    if (!finger.isDown) {
      val pointer = pointerProvider()
      ownsFinger = true
      finger.onLifted = {
        ownsFinger = false
        // Continue with the remaining distance, only while still scrolling
        val stillScrolling = System.currentTimeMillis() - lastWheelAt < idleMs
        if (!stillScrolling) {
          pending.set(0f, 0f)
        } else if (pending.x != 0f || pending.y != 0f) {
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
      // Finger is used for something else (e.g. a drag)
      pending.set(0f, 0f)
      return false
    }
    return true
  }

  // ---------------------------------------------------------------------------
  // Mode: Swipes (collected, short one-shot swipes)
  // ---------------------------------------------------------------------------

  private val swipeWindowMs: Long
    get() = (30 + smoothing * 170).toLong()

  private var flushScheduled = false

  private fun scheduleSwipe() {
    // Flushed when the running swipe completes
    if (swipeInFlight || flushScheduled) return
    flushScheduled = true
    handler.postDelayed(flushRunnable, swipeWindowMs)
  }

  private fun flushSwipe() {
    flushScheduled = false
    if (swipeInFlight || (pending.x == 0f && pending.y == 0f)) return
    val distance = PointF(pending.x, pending.y)
    pending.set(0f, 0f)
    dispatchSwipe(distance, (swipeWindowMs * 1.5f).toLong().coerceAtLeast(60L)) {
      if (pending.x != 0f || pending.y != 0f) scheduleSwipe()
    }
  }

  // ---------------------------------------------------------------------------
  // Mode: Flick (one swipe with momentum per movement)
  // ---------------------------------------------------------------------------

  private fun flick() {
    if (swipeInFlight || (pending.x == 0f && pending.y == 0f)) return
    val distance = PointF(pending.x, pending.y)
    pending.set(0f, 0f)
    // Shorter = faster = more momentum
    dispatchSwipe(distance, (80 + smoothing * 120).toLong()) {}
  }

  // ---------------------------------------------------------------------------
  // Mode: Steps (fixed, slow steps without momentum)
  // ---------------------------------------------------------------------------

  private fun applySteps() {
    val threshold = STEP_THRESHOLD_DP * density
    val length = hypot(pending.x, pending.y)
    if (length < threshold) return
    if (ownsFinger && finger.isDown) return // previous step still running
    if (!takeFinger()) return

    val bounds = boundsProvider()
    val step = bounds.y * STEP_SCREEN_FRACTION
    val dirX = sign(pending.x)
    val dirY = sign(pending.y)
    // One step consumes one threshold, never queue more than one extra step
    pending.set(
      (pending.x - dirX * threshold).let { if (sign(it) != dirX) 0f else it },
      (pending.y - dirY * threshold).let { if (sign(it) != dirY) 0f else it },
    )
    if (hypot(pending.x, pending.y) > threshold) {
      pending.set(dirX * threshold * abs(dirX), dirY * threshold * abs(dirY))
    }

    val start = finger.position
    finger.moveTo(start.x + dirX * step, start.y + dirY * step)
    finger.up()
  }

  // ---------------------------------------------------------------------------
  // Mode: Page (accessibility scroll actions, no gestures)
  // ---------------------------------------------------------------------------

  private fun applyPage() {
    val threshold = PAGE_THRESHOLD_DP * density
    val now = System.currentTimeMillis()
    if (hypot(pending.x, pending.y) < threshold) return
    if (now - lastPageScrollAt < (250 + smoothing * 350).toLong()) {
      // Too fast, drop the movement, a page is a big jump
      pending.set(0f, 0f)
      return
    }

    val dirX = sign(pending.x).toInt()
    val dirY = sign(pending.y).toInt()
    pending.set(0f, 0f)
    lastPageScrollAt = now

    val pointer = pointerProvider()
    if (!nodeScroller(pointer.x, pointer.y, dirX, dirY)) {
      log.debug { "No scrollable view at the pointer" }
    }
  }

  // ---------------------------------------------------------------------------
  // One-shot swipes
  // ---------------------------------------------------------------------------

  /** Swipe the finger by [distance], starting at the pointer. */
  private fun dispatchSwipe(distance: PointF, durationMs: Long, onDone: () -> Unit) {
    val bounds = boundsProvider()
    val pointer = pointerProvider()
    val margin = 2f
    val startX = pointer.x.coerceIn(margin, bounds.x - margin)
    val startY = pointer.y.coerceIn(margin, bounds.y - margin)
    val endX = (startX + distance.x).coerceIn(margin, bounds.x - margin)
    val endY = (startY + distance.y).coerceIn(margin, bounds.y - margin)
    if (hypot(endX - startX, endY - startY) < 1f) return

    val path =
      Path().apply {
        moveTo(startX, startY)
        lineTo(endX, endY)
      }
    swipeInFlight = true
    val dispatched =
      service.dispatchGesture(
        GestureDescription.Builder()
          .addStroke(StrokeDescription(path, 0, durationMs.coerceAtLeast(1L)))
          .build(),
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
    if (!dispatched) swipeInFlight = false
  }

  companion object {
    private val log = KLoggingManager.logger(ScrollController::class.java.simpleName)

    const val DEFAULT_SMOOTHING = 0.4f

    private const val WHEEL_DELTA_PER_NOTCH = 120f

    /**
     * Distance per 120 wheel units. macOS sends every trackpad event as
     * (accelerated) lines, so this must be small.
     */
    private const val NOTCH_DP = 16f

    /** Max. distance waiting to be applied, relative to the screen size */
    private const val MAX_PENDING_SCREEN_FRACTION = 0.3f

    private const val EDGE_FRACTION = 0.05f

    /** Flick before the movement ended, when it got this long */
    private const val FLICK_EARLY_SCREEN_FRACTION = 0.25f

    /** Wheel distance (scaled like the finger movement) for one step */
    private const val STEP_THRESHOLD_DP = 40f

    /** Distance of one step, relative to the screen height */
    private const val STEP_SCREEN_FRACTION = 0.12f

    /** Wheel distance (scaled like the finger movement) for one page scroll */
    private const val PAGE_THRESHOLD_DP = 60f
  }
}
