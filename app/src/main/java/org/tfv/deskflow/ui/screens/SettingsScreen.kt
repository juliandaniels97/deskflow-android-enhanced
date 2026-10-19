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

package org.tfv.deskflow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.tfv.deskflow.R
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.components.ClientCertificate
import org.tfv.deskflow.components.DiscoveredServer
import org.tfv.deskflow.components.ServerDiscovery
import org.tfv.deskflow.data.appPrefsStore
import org.tfv.deskflow.data.models.AppPrefsKt.inputConfig
import org.tfv.deskflow.data.models.copy
import org.tfv.deskflow.services.input.ScrollMode
import org.tfv.deskflow.data.aidl.ScreenState
import org.tfv.deskflow.data.aidl.ServerState
import org.tfv.deskflow.ui.annotations.PreviewAll
import org.tfv.deskflow.ui.components.AppState
import org.tfv.deskflow.ui.components.DeskflowCard
import org.tfv.deskflow.ui.components.DeskflowCardSubtitle
import org.tfv.deskflow.ui.components.DeskflowCardTitle
import org.tfv.deskflow.ui.components.IAppState
import org.tfv.deskflow.ui.components.LocalSnackbarHostState
import org.tfv.deskflow.ui.components.deskflowCardDefaultContainerModifier
import org.tfv.deskflow.ui.components.deskflowCardStyleDefaults
import org.tfv.deskflow.ui.components.preview.PreviewDeskflowThemedRoot

private val log = KLoggingManager.logger("SettingsScreen")

@Serializable data object SettingsScreenRoute

fun NavController.navigateToSettingsScreen(navOptions: NavOptions) =
  navigate(route = SettingsScreenRoute, navOptions)

fun NavGraphBuilder.settingsScreen(appState: IAppState) {
  composable<SettingsScreenRoute> { SettingsScreenRoute(appState = appState) }
}

@Composable
internal fun SettingsScreenRoute(appState: IAppState) {
  val state by appState.connectionStateFlow.collectAsStateWithLifecycle()
  val screenState = state.screen
  val isConnected = state.isConnected
  val snackbarHost = LocalSnackbarHostState.current
  val scope = rememberCoroutineScope()
  val ctx = LocalContext.current
  val appPrefs by
    ctx.appPrefsStore.data.collectAsStateWithLifecycle(initialValue = null)
  val serverDiscovery = remember(ctx) { ServerDiscovery(ctx) }
  val scrollSettings =
    appPrefs?.input?.let { input ->
      ScrollSettings(
        invertY = input.scrollInvert,
        invertX = input.scrollInvertX,
        mode = input.scrollMode,
        smoothingPercent =
          if (input.hasScrollSmoothing()) input.scrollSmoothing
          else ScrollSettings.DEFAULT_SMOOTHING_PERCENT,
        speedPercent =
          if (input.scrollSpeedPercent <= 0) ScrollSettings.DEFAULT_SPEED_PERCENT
          else input.scrollSpeedPercent,
      )
    }
  SettingsScreen(
    uiState =
      if (screenState == null || scrollSettings == null) SettingsUiState.Loading
      else SettingsUiState.Success(isConnected, screenState, scrollSettings),
    onChange = { newScreenState, newScrollSettings ->
      if (appState !is AppState) return@SettingsScreen

      appState.serviceClient.updateScreenState(newScreenState)
      scope.launch {
        ctx.appPrefsStore.updateData { prefs ->
          prefs.copy {
            input = inputConfig {
              scrollInvert = newScrollSettings.invertY
              scrollInvertX = newScrollSettings.invertX
              scrollMode = newScrollSettings.mode
              scrollSmoothing = newScrollSettings.smoothingPercent
              scrollSpeedPercent = newScrollSettings.speedPercent
            }
          }
        }
        snackbarHost.showSnackbar(ctx.getString(R.string.toast_settings_saved))
      }

    },
    onDiscover = { serverDiscovery.discover() },
    onCancel = { appState.navigateToHome() },
  )
}

data class ScrollSettings(
  val invertY: Boolean = false,
  val invertX: Boolean = false,
  val mode: Int = 0,
  val smoothingPercent: Int = DEFAULT_SMOOTHING_PERCENT,
  val speedPercent: Int = DEFAULT_SPEED_PERCENT,
) {
  companion object {
    const val DEFAULT_SPEED_PERCENT = 100
    const val DEFAULT_SMOOTHING_PERCENT = 40
    const val MIN_SPEED_PERCENT = 25
    const val MAX_SPEED_PERCENT = 300
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  uiState: SettingsUiState,
  onChange: (ScreenState, ScrollSettings) -> Unit,
  onDiscover: suspend () -> List<DiscoveredServer>,
  onCancel: () -> Unit,
) {

  when (uiState) {
    is SettingsUiState.Loading -> {
      CircularProgressIndicator(
        modifier = Modifier.width(64.dp),
        color = MaterialTheme.colorScheme.secondary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
      )
    }

    is SettingsUiState.Success -> {
      val textFieldColors =
        TextFieldDefaults
          .colors( //        unfocusedContainerColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
          )
      val focusRequester = remember { FocusRequester() }
      LaunchedEffect(Unit) {
        focusRequester.requestFocus() //        keyboardController?.show()
      }
      val screenValue = uiState.screen
      var screenName by remember(screenValue) { mutableStateOf(screenValue.name) }
      var address by remember(screenValue) { mutableStateOf(screenValue.server.address) }
      var port by remember(screenValue) { mutableIntStateOf(screenValue.server.port) }
      var useTls by remember(screenValue) { mutableStateOf(screenValue.server.useTls) }
      var autoDiscover by remember(screenValue) { mutableStateOf(screenValue.server.autoDiscover) }
      val scrollValue = uiState.scroll
      var scrollInvertY by remember(scrollValue) { mutableStateOf(scrollValue.invertY) }
      var scrollInvertX by remember(scrollValue) { mutableStateOf(scrollValue.invertX) }
      var scrollMode by remember(scrollValue) { mutableIntStateOf(scrollValue.mode) }
      var scrollSmoothing by remember(scrollValue) {
        mutableIntStateOf(scrollValue.smoothingPercent)
      }
      var scrollSpeed by remember(scrollValue) { mutableIntStateOf(scrollValue.speedPercent) }
      var isDirty by remember(screenValue, scrollValue) { mutableStateOf(false) }

      val discoveryScope = rememberCoroutineScope()
      var isSearching by remember { mutableStateOf(false) }
      var hasSearched by remember { mutableStateOf(false) }
      var discoveredServers by remember { mutableStateOf(emptyList<DiscoveredServer>()) }

      val saveChanges ={
        onChange(
          ScreenState().apply {
            name = screenName
            server =
              ServerState().apply {
                this.address = address
                this.port = port
                this.useTls = useTls
                this.autoDiscover = autoDiscover
              }
          },
          ScrollSettings(
            invertY = scrollInvertY,
            invertX = scrollInvertX,
            mode = scrollMode,
            smoothingPercent = scrollSmoothing,
            speedPercent = scrollSpeed,
          ),
        )
      }

      val textStyle = MaterialTheme.typography.bodyMedium
      val textStyleCharWidthDp =
        with(LocalDensity.current) { textStyle.fontSize.toDp() }

      val innerScrollState = rememberScrollState()

      Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment =
          Alignment
            .CenterHorizontally, // (optionally) center them horizontally too
      ) {
        DeskflowCard(
          header = {
            DeskflowCardTitle(R.string.settings_screen_title)
            DeskflowCardSubtitle(R.string.settings_screen_instructions)
          },
          style =
            deskflowCardStyleDefaults(
              containerModifier =
                deskflowCardDefaultContainerModifier().padding(vertical = 16.dp)
            ),
          useFooterStyle = false,
          useContentStyle = false,
          footer = {
            /** Bottom action bar */
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .background(
                    color = MaterialTheme.colorScheme.secondaryContainer
                  )
            ) {
              CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSecondary
              ) {
                Row(
                  horizontalArrangement =
                    Arrangement.spacedBy(8.dp, alignment = Alignment.End),
                  modifier = Modifier.fillMaxWidth().padding(8.dp),
                ) {
                  Button(
                    onClick = { onCancel() },
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.textButtonColors(),
                  ) {
                    Text(stringResource(R.string.button_cancel))
                  }
                  Button(
                    onClick = {
                      when {
                        isDirty -> saveChanges()
                        else -> onCancel()
                      }
                    },
                    shape = MaterialTheme.shapes.small,
                    colors =
                      ButtonDefaults.filledTonalButtonColors(
                        containerColor =
                          MaterialTheme.colorScheme.onSecondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                      ),
                  ) {
                    Text(
                      stringResource(
                        when {
                          isDirty -> R.string.button_save
                          else -> R.string.button_done
                        }
                      )
                    )
                  }
                }
              }
            }
          },
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
              Modifier.fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(innerScrollState),
          ) {
            Row(
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
            ) {
              TextField(
                label = {
                  Text(
                    stringResource(R.string.settings_screen_screen_name_label)
                  )
                },
                singleLine = true,
                value = screenName,
                colors = textFieldColors,
                onValueChange = {
                  screenName = it
                  isDirty = true
                },
                placeholder = {
                  Text(
                    stringResource(
                      R.string.settings_screen_screen_name_placeholder
                    )
                  )
                },
                modifier =
                  Modifier.focusRequester(focusRequester)
                    .background(Color.Transparent)
                    .fillMaxWidth(),
              )
            }

            Row(
              horizontalArrangement =
                Arrangement.spacedBy(
                  8.dp
                ), // verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
            ) {
              TextField(
                value = address,
                maxLines = 1,
                label = {
                  Text(
                    stringResource(
                      R.string.settings_screen_screen_address_label
                    )
                  )
                },
                onValueChange = {
                  address = it
                  isDirty = true
                },
                placeholder = {
                  Text(
                    stringResource(
                      R.string.settings_screen_screen_address_placeholder
                    )
                  )
                },
                singleLine = true,
                colors = textFieldColors,
                modifier = Modifier.background(Color.Transparent).weight(1f),
              )

              TextField(
                value = port.toString(),
                colors = textFieldColors,
                label = {
                  Text(
                    stringResource(R.string.settings_screen_screen_port_label)
                  )
                },
                onValueChange = { newPort ->
                  if (newPort.all { it.isDigit() }) {
                    port = newPort.toInt()
                    isDirty = true
                  }
                },
                placeholder = {
                  Text(
                    stringResource(
                      R.string.settings_screen_screen_port_placeholder
                    )
                  )
                },
                keyboardOptions =
                  KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                  ),
                singleLine = true,
                modifier =
                  Modifier.background(Color.Transparent)
                    .width(textStyleCharWidthDp * 6f),
              )

              Column(
                verticalArrangement =
                  Arrangement.spacedBy(
                    space = 0.dp,
                    alignment = Alignment.CenterVertically,
                  ),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 2.dp),
              ) {
                Text(
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface,
                  text =
                    stringResource(R.string.app_prefs_screen_server_use_tls),
                )
                Checkbox(
                  checked = useTls,
                  onCheckedChange = {
                    useTls = it
                    isDirty = true
                  },
                )
              }
            }

            if (useTls) {
              val fingerprint by
                produceState<String?>(initialValue = null) {
                  value =
                    withContext(Dispatchers.IO) {
                      runCatching { ClientCertificate.fingerprint() }.getOrNull()
                    }
                }
              Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  text = stringResource(R.string.settings_screen_tls_fingerprint_label),
                )
                SelectionContainer {
                  Text(
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    text = fingerprint ?: "\u2026",
                  )
                }
              }
            }

            SettingsSwitchRow(
              label = stringResource(R.string.settings_screen_auto_discover_label),
              description = stringResource(R.string.settings_screen_auto_discover_description),
              checked = autoDiscover,
              onCheckedChange = {
                autoDiscover = it
                isDirty = true
              },
            )

            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              OutlinedButton(
                enabled = !isSearching,
                shape = MaterialTheme.shapes.small,
                onClick = {
                  isSearching = true
                  discoveryScope.launch {
                    discoveredServers = runCatching { onDiscover() }.getOrDefault(emptyList())
                    isSearching = false
                    hasSearched = true
                  }
                },
              ) {
                Text(stringResource(R.string.settings_screen_discover_button))
              }
              if (isSearching) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  strokeWidth = 2.dp,
                )
              }
            }

            if (hasSearched && !isSearching && discoveredServers.isEmpty()) {
              Text(
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                text = stringResource(R.string.settings_screen_discover_none_found),
              )
            }

            discoveredServers.forEach { server ->
              val isSelected = server.address == address && server.port == port
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                  Modifier.fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(
                      if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                      else Color.Transparent
                    )
                    .clickable {
                      address = server.address
                      port = server.port
                      if (server.verified) useTls = server.useTls
                      isDirty = true
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    text = server.displayName,
                  )
                  Text(
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    text =
                      buildString {
                        append("${server.address}:${server.port}")
                        if (server.useTls) append(" · TLS")
                        if (!server.verified) append(" · ?")
                      },
                  )
                }
                if (isSelected) {
                  Text(
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    text = "\u2713",
                  )
                }
              }
            }

            HorizontalDivider()

            Text(
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurface,
              text = stringResource(R.string.settings_screen_scrolling_title),
            )

            Text(
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
              text = stringResource(R.string.settings_screen_scroll_mode_label),
            )
            Column(modifier = Modifier.fillMaxWidth()) {
              SCROLL_MODE_OPTIONS.forEach { (mode, labelRes, descriptionRes) ->
                val modeId = mode.id
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier =
                    Modifier.fillMaxWidth()
                      .clip(MaterialTheme.shapes.small)
                      .clickable {
                        scrollMode = modeId
                        isDirty = true
                      }
                      .padding(vertical = 4.dp),
                ) {
                  RadioButton(
                    selected = scrollMode == modeId,
                    onClick = {
                      scrollMode = modeId
                      isDirty = true
                    },
                  )
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurface,
                      text = stringResource(labelRes),
                    )
                    Text(
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      text = stringResource(descriptionRes),
                    )
                  }
                }
              }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
              Text(
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                text =
                  stringResource(
                    R.string.settings_screen_scroll_smoothing_label,
                    scrollSmoothing,
                  ),
              )
              Slider(
                value = scrollSmoothing.toFloat(),
                valueRange = 0f..100f,
                onValueChange = {
                  scrollSmoothing = (it / 5f).roundToInt() * 5
                  isDirty = true
                },
              )
              Text(
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                text = stringResource(R.string.settings_screen_scroll_smoothing_description),
              )
            }

            Text(
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              text = stringResource(R.string.settings_screen_scroll_invert_description),
            )

            SettingsSwitchRow(
              label = stringResource(R.string.settings_screen_scroll_invert_y_label),
              checked = scrollInvertY,
              onCheckedChange = {
                scrollInvertY = it
                isDirty = true
              },
            )

            SettingsSwitchRow(
              label = stringResource(R.string.settings_screen_scroll_invert_x_label),
              checked = scrollInvertX,
              onCheckedChange = {
                scrollInvertX = it
                isDirty = true
              },
            )

            Column(modifier = Modifier.fillMaxWidth()) {
              Text(
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                text =
                  stringResource(R.string.settings_screen_scroll_speed_label, scrollSpeed),
              )
              Slider(
                value = scrollSpeed.toFloat(),
                valueRange =
                  ScrollSettings.MIN_SPEED_PERCENT.toFloat()..ScrollSettings.MAX_SPEED_PERCENT
                      .toFloat(),
                onValueChange = {
                  scrollSpeed = ((it / 5f).roundToInt() * 5)
                  isDirty = true
                },
              )
            }
          }
        }
      }
    }
  }
}

private val SCROLL_MODE_OPTIONS =
  listOf(
    Triple(
      ScrollMode.Finger,
      R.string.settings_screen_scroll_mode_finger,
      R.string.settings_screen_scroll_mode_finger_description,
    ),
    Triple(
      ScrollMode.Swipes,
      R.string.settings_screen_scroll_mode_swipes,
      R.string.settings_screen_scroll_mode_swipes_description,
    ),
    Triple(
      ScrollMode.Flick,
      R.string.settings_screen_scroll_mode_flick,
      R.string.settings_screen_scroll_mode_flick_description,
    ),
    Triple(
      ScrollMode.Steps,
      R.string.settings_screen_scroll_mode_steps,
      R.string.settings_screen_scroll_mode_steps_description,
    ),
    Triple(
      ScrollMode.Page,
      R.string.settings_screen_scroll_mode_page,
      R.string.settings_screen_scroll_mode_page_description,
    ),
  )

@Composable
private fun SettingsSwitchRow(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  description: String? = null,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        text = label,
      )
      if (description != null) {
        Text(
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          text = description,
        )
      }
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Preview
@Composable
fun SettingsScreenPreviewLoading() {
  PreviewDeskflowThemedRoot {
    SettingsScreen(
      uiState = SettingsUiState.Loading,
      onChange = { _, _ -> },
      onDiscover = { emptyList() },
      onCancel = {},
    )
  }
}

@PreviewAll
@Composable
fun SettingsScreenPreviewSuccess() {
  PreviewDeskflowThemedRoot { appState ->
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val snackbarHost = LocalSnackbarHostState.current

    var screenStateValue by remember {
      mutableStateOf(
        ScreenState().apply {
          name = "AndroidScreen"
          server =
            ServerState().apply {
              address = "localhost"
              port = 24800
              useTls = false
              autoDiscover = true
            }
        }
      )
    }
    SettingsScreen(
      uiState = SettingsUiState.Success(true, screenStateValue, ScrollSettings()),
      onChange = { screenState, _ ->
        screenStateValue = screenState
        scope.launch {
          snackbarHost.showSnackbar(ctx.getString(R.string.toast_settings_saved))
        }
      },
      onDiscover = {
        listOf(
          DiscoveredServer("192.168.1.20", 24800, "Julians-MacBook-Pro", verified = true, useTls = true)
        )
      },
      onCancel = {},
    )
  }
}

sealed class SettingsUiState {
  data object Loading : SettingsUiState()

  data class Success(
    val isConnected: Boolean,
    val screen: ScreenState,
    val scroll: ScrollSettings,
  ) : SettingsUiState()
}
