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

import android.graphics.PointF
import android.os.Handler

/**
 * Translates Deskflow mouse wheel events into touch scrolling, similar to
 * how scrolling behaves on the desktop (macOS) clients.
 *
 * A virtual finger is placed at the mouse pointer and dragged in the
 * opposite direction of the scroll, so the content under the pointer
 * scrolls smoothly (vertically & horizontally). When the wheel is idle the
 * finger is lifted without a fling; when the finger reaches the edge of the
 * screen it is lifted and placed back at the pointer.
 */
class ScrollController(
  private val finger: VirtualFinger,
  private val handler: Handler,
  private val pointerProvider: () -> PointF,
  private val boundsProvider: () -> PointF,
) {

  /** Invert the scroll direction ("natural" scrolling). */
  @Volatile var invert: Boolean = false

  /** Scroll speed multiplier, `1.0` = default. */
  @Volatile var speed: Float = 1f

  /** Scroll distance not yet applied to the finger (in px). */
  private val pending = PointF()

  /** `true` while the finger is used by this controller. */
  private var ownsFinger = false

  private val idleLift = Runnable {
    if (ownsFinger && finger.isDown) finger.up()
  }

  /**
   * Handle a wheel event. Deskflow uses 120 units per wheel notch; trackpads
   * deliver many smaller deltas.
   */
  fun onWheel(xDelta: Int, yDelta: Int) {
    val bounds = boundsProvider()
    val notchPx = bounds.y * NOTCH_SCREEN_FRACTION * speed
    val direction = if (invert) -1f else 1f

    // Wheel up (positive y) shows content above -> finger moves down.
    // Wheel right (positive x) shows content on the right -> finger moves left.
    pending.x += -xDelta / WHEEL_DELTA_PER_NOTCH * notchPx * direction
    pending.y += yDelta / WHEEL_DELTA_PER_NOTCH * notchPx * direction

    handler.removeCallbacks(idleLift)
    handler.postDelayed(idleLift, IDLE_LIFT_MS)

    apply()
  }

  /** Abort scrolling, e.g. when a mouse button is pressed. */
  fun reset() {
    handler.removeCallbacks(idleLift)
    pending.set(0f, 0f)
    if (ownsFinger) {
      ownsFinger = false
      finger.cancel()
    }
  }

  private fun apply() {
    if (pending.x == 0f && pending.y == 0f) return

    if (ownsFinger && finger.isLifting) {
      // Wait until the finger was lifted, then continue at the pointer
      return
    }

    if (!finger.isDown) {
      val pointer = pointerProvider()
      ownsFinger = true
      finger.onLifted = {
        ownsFinger = false
        // Continue with the remaining distance, if any
        if (pending.x != 0f || pending.y != 0f) handler.post { apply() }
      }
      finger.down(pointer.x, pointer.y)
    } else if (!ownsFinger) {
      // Finger is used for something else (e.g. a drag)
      pending.set(0f, 0f)
      return
    }

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

  companion object {
    private const val WHEEL_DELTA_PER_NOTCH = 120f
    private const val NOTCH_SCREEN_FRACTION = 0.06f
    private const val EDGE_FRACTION = 0.03f
    private const val IDLE_LIFT_MS = 140L
  }
}
