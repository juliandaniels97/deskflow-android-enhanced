# Deskflow Android (Enhanced)

A **near** fully functional [Deskflow (deskflow.org)](https://deskflow.org) client for Android devices.
Control your Android phone or tablet with the keyboard, mouse and trackpad of your desktop (e.g. your Mac),
just like any other Deskflow screen.

This is an enhanced fork of [jglanz/deskflow-android](https://github.com/jglanz/deskflow-android) that adds smooth
content scrolling, full mouse button support, automatic server discovery and TLS client certificates.

> NOTE: Everything here in, assumes you already use Deskflow on your desktop &
> know how to configure a client/screen

## Table of Contents

<!-- TOC -->
* [Deskflow Android (Enhanced)](#deskflow-android-enhanced)
  * [Table of Contents](#table-of-contents)
  * [Features & Limitations](#features--limitations)
  * [Requirements](#requirements)
  * [Installation](#installation)
    * [Download the APK](#download-the-apk)
    * [Build manually](#build-manually)
  * [Config](#config-)
    * [Deskflow Server](#deskflow-server)
      * [Add the Android screen](#add-the-android-screen)
      * [TLS/SSL Security](#tlsssl-security)
    * [Deskflow Android (this app)](#deskflow-android-this-app)
      * [Setup Connection](#setup-connection)
      * [Automatic server discovery](#automatic-server-discovery)
      * [Scrolling](#scrolling)
      * [Mouse buttons](#mouse-buttons)
      * [Keyboard (IME)](#keyboard-ime-)
      * [Start/Stop Connection](#startstop-connection)
  * [Troubleshooting](#troubleshooting)
<!-- TOC -->

## Features & Limitations

- Shortcuts & Hotkeys
  - App Switching (`Command+Tab` or `Alt+Tab`)
  - Arrow keys for general navigation
  - `Escape` for `Back` functionality
  - +++
- Clipboard integration
  - Text (of any kind) is supported
  - __Bitmap is **NOT** supported currently__
- Content scrolling with the mouse wheel or trackpad
  - Vertical **and** horizontal, nothing keeps scrolling once you stop
  - 5 selectable scroll modes, adjustable smoothing & speed
  - Scroll direction invertible separately for the X and Y axis (e.g. for macOS natural scrolling)
- Mouse buttons
  - Click & drag (drag lists, sliders, move icons, ...)
  - Hold the left button for a long press (& long-press-drag)
  - Right click = long press (context menus)
  - "Back" mouse button = Android back
- Automatic server discovery
  - Finds the Deskflow server (e.g. your Mac) on the local network, no hostname or IP address needed
  - Uses mDNS/Bonjour & a scan of the local network, detects plain & TLS servers
  - "Search network" in the settings lists all found servers to pick from
- TLS incl. client certificates
  - Works with "Require client certificates" enabled on the server (the Deskflow default)
- Clear connection errors
  - If the server rejects the screen name (unknown or already in use), the app disconnects and logs what to fix

## Requirements

- Android 14 or newer
- 64-bit ARM device (`arm64-v8a`)
- Deskflow on your desktop, running as server

## Installation

### Download the APK

The latest APK is built automatically from `master`:

[DeskflowAndroid-v1.0.14-arm64-v8a.apk](https://github.com/juliandaniels97/deskflow-android-enhanced/releases/download/apk-master/DeskflowAndroid-v1.0.14-arm64-v8a.apk)

Open the link on your Android device and allow the installation from unknown sources.
All builds are signed with the same key, so new versions install as an update over the existing app.

> NOTE: If the original Deskflow Android app is installed, uninstall it first (different signature).

### Build manually

1. Clone the repository:
   ```bash
   git clone https://github.com/juliandaniels97/deskflow-android-enhanced.git
   ```
2. Build the APK (requires JDK 17 & the Android SDK):
   ```bash
   ./gradlew :app:assembleRelease
   ```
   The APK is written to `app/build/outputs/apk/release/`.
   Alternatively open the project in Android Studio, then build and run the app on your device.

## Config

### Deskflow Server

#### Add the Android screen

1. Make sure the server computer (e.g. your Mac) has its **own** name, `Computer name` in the Deskflow main window.
2. Open `Configure Server` and drag a **new** screen next to your computer.
3. Name it exactly like the `Screen Name` in the Android app (default: `Deskflow-Android`) and save.

#### TLS/SSL Security

TLS is fully supported, including "Require client certificates".

The app creates its own client certificate on the first TLS connection (stored in the Android Keystore,
it never leaves the device). The first time the app connects, the Deskflow app on the server asks whether to
trust the fingerprint of this device. Compare it with the fingerprint shown in the app settings
(visible when `Use TLS` is enabled) and accept it. From then on the connection is established automatically.

### Deskflow Android (this app)

On first launch, you'll see a gear in the top right corner of the app,
press the gear (highlighted in the screenshot below) to open the configuration screen.

![Config Screenshot](docs/assets/screenshots/screenshot_tablet_home.png)

#### Setup Connection

![Config Screenshot](docs/assets/screenshots/screenshot_tablet_config.png)

- `Screen Name` the name you configured in the Deskflow Server.
- `Host` & `Port` of your Deskflow Server, or use `Search network` to pick a found server.
- `Use TLS` if your Deskflow Server is configured for TLS (set automatically when picking a found server).

Press `Save` to save your configuration and return to the home screen.

> NOTE: As soon as you press `Save`, the app will attempt to connect to the Deskflow Server.

#### Automatic server discovery

- `Search network` searches the local network and lists all Deskflow servers found, with computer name,
  address and whether TLS is used. Tap a server to use it.
- `Find server automatically` (enabled by default): when no server is configured, or the configured one
  can't be reached for a while, the app searches the network and connects to the server found.
  It also follows the server when its IP address changes.

> NOTE: On the very first app start nothing is connected automatically. Automatic discovery starts
> after you saved the connection settings once.

#### Scrolling

Mouse wheel & trackpad scrolling is configured in the `Scrolling` section of the settings.

| Scroll mode               | How it works                                                                    |
|---------------------------|---------------------------------------------------------------------------------|
| 1. Virtual finger         | A finger stays on the screen and follows the wheel/trackpad directly            |
| 2. Short swipes           | Scrolling is collected briefly and applied as short swipes, one after another   |
| 3. Flick (momentum)       | One swipe with momentum per scroll movement, like flicking with a finger        |
| 4. Fixed steps            | Even, calm steps without momentum                                               |
| 5. Page by page           | No gestures, the scrollable view under the pointer scrolls by pages (very stable) |

- `Smoothing` higher is smoother but reacts a bit later (modes 1-3, and the pause between pages in mode 5).
- `Invert vertical / horizontal scrolling` invert the direction per axis.
- `Scroll speed` 25% - 300%.

Every scroll movement is locked to its main direction, so the small sideways movement of a trackpad doesn't
move tabs, carousels or home screen pages.

> TIP: macOS keeps sending scroll events after a quick swipe on the trackpad (inertia). To disable it:
> System Settings → Accessibility → Pointer Control → Trackpad Options → Scrolling "without inertia".

#### Mouse buttons

| Mouse                               | Android                                    |
|-------------------------------------|--------------------------------------------|
| Left click                          | Tap                                        |
| Left button held & moved            | Drag                                       |
| Left button held without moving     | Long press (moving afterwards drags)       |
| Right click                         | Long press (e.g. context menu)             |
| Back button                         | Back                                       |

#### Keyboard (IME)

> IMPORTANT: The Deskflow Android IME, **When Connected to a Deskflow Server**, will always attempt to force the `Deskflow Android Keyboard` ensuring seamless work between Deskflow Server & Client.
> In the case you don't want to use the Deskflow Android Keyboard, you can stop the connection to the Deskflow Server using the button in the top right corner of the app.

The Deskflow Android IME (Input Method Editor) is a custom keyboard that will show the current connection status and will look
like this screen shot when connected:

![IME Screenshot](docs/assets/screenshots/screenshot_keyboard_ime_active.png)

#### Start/Stop Connection

There is a button in the top right corner of the app to start/stop the connection to the Deskflow Server.

This is useful if you want to either use the regular soft/hard keyboard functionality or simply want to disconnect from the Deskflow Server.

## Troubleshooting

| Server log / app log                                    | Solution                                                                                                 |
|---------------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| `a client with name "..." is already connected`         | The server computer itself or another device uses the same name, give each a unique name                  |
| `unrecognised client name "..."`                        | Add a screen with exactly the app's `Screen Name` in `Configure Server`                                  |
| `peer did not return a certificate`                     | Enable `Use TLS` in the app and accept the fingerprint on the server                                     |
| `Search network` finds nothing                          | Make sure the server is running and both devices are in the same Wi-Fi/network                           |
