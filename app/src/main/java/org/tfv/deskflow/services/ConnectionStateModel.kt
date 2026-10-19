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

package org.tfv.deskflow.services

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.data.aidl.ConnectionState
import org.tfv.deskflow.data.aidl.ServerState
import org.tfv.deskflow.data.models.AppPrefs
import org.tfv.deskflow.ext.ConnectionStateDefaults
import org.tfv.deskflow.ext.copy
import org.tfv.deskflow.ext.getScreenSize

class ConnectionStateModel(private val context: Context) {

  companion object {
    private val log =
      KLoggingManager.logger(ConnectionStateModel::class.java.simpleName)
  }

  private val editableState = MutableStateFlow(ConnectionStateDefaults(context))

  /** Combine states to make ConnectionState */
  val state = editableState.asStateFlow()

  /**
   * Updates the connection state with the given [appPrefs].
   * If the current screen configuration matches the app preferences, it does nothing.
   * Otherwise, it updates the screen name and server configuration.
   */
  fun updateScreenFromAppPrefs(appPrefs: AppPrefs) {
    updateState { currentState ->
      val screenSize = context.getScreenSize()

      val currentScreen = currentState.screen
      val currentServer = currentScreen.server
      if (
        currentScreen.width == screenSize.px.width &&
        currentScreen.height == screenSize.px.height &&
        currentServer.address == appPrefs.screen.server.address &&
        currentServer.port == appPrefs.screen.server.port &&
        currentServer.useTls == appPrefs.screen.server.useTls &&
        currentServer.autoDiscover == !appPrefs.screen.server.autoDiscoverDisabled &&
        currentScreen.name == appPrefs.screen.name) {
        
        log.debug { "AppPrefs are unchanged" }
        return@updateState currentState
      }

      currentState.copy(
        screen =
          currentScreen.copy(
            name = appPrefs.screen.name,
            width = screenSize.px.width,
            height = screenSize.px.height,
            server =
              ServerState().apply {
                address = appPrefs.screen.server.address
                port = appPrefs.screen.server.port
                useTls = appPrefs.screen.server.useTls
                autoDiscover = !appPrefs.screen.server.autoDiscoverDisabled
              }
          )
      )

    }
  }

  fun updateState(mutator: (ConnectionState) -> ConnectionState) {
    editableState.update { mutator(it) }
  }

}

