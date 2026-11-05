# MorningSkip

This development branch contains an **unverified native-receiver candidate**. It sends a
bounded, package-scoped broadcast to the greeting receiver registered during native window
attachment. It does not require an OFF-screen accessibility tree. The regular download
below still points to the stable release. This candidate is available separately as a
[1.0.5-experimental.1 prerelease](https://github.com/jamatnat/morning-skip/releases/tag/v1.0.5-experimental.1).

**[⬇ Download APK](https://github.com/jamatnat/morning-skip/releases/latest/download/MorningSkip.apk)** · [Installation & setup](#installation)

Automatically dismiss the **“Good morning” greeting on OnePlus Watch 3**, including its afternoon and evening variants.

- Runs through an Accessibility service; no need to keep the app screen open.
- One switch to turn automatic closing on or off.
- Local diagnostics for troubleshooting. No root, screenshots, analytics or Internet access.

Developed on OnePlus Watch 3. Other devices and firmware versions are untested; complete prevention of the rare firmware freeze has not been verified on-device.

## Installation

[Download MorningSkip.apk](https://github.com/jamatnat/morning-skip/releases/latest/download/MorningSkip.apk), then choose how to install it on your watch:

- **Android phone:** use [WearLoad](https://wearload.github.io/index_en.html). Follow its setup guide or [video tutorial](https://www.youtube.com/watch?v=cRqN0rvqA00), select `MorningSkip.apk` on your phone, and confirm installation on the watch.
- **Computer:** follow the [ADB installation instructions](docs/GUIDE.md#install-over-wireless-adb).

After installation, enable MorningSkip as described below.

## Enable

1. Open MorningSkip and tap **Open Accessibility**.
2. Enable the **MorningSkip** service in watch settings.
3. Return to the app and wait for **Accessibility: ON**.
4. Set **Good Morning Auto-close: ON**. **Service: ON** turns green when Accessibility is connected and Auto-close is enabled.

You can now leave the app. Set Auto-close to **OFF** to stop closing greetings, or disable the Accessibility service to stop logging as well.

## How it works

The candidate reacts to a greeting signal by requesting closure through the vendor's
attach-scoped receiver. The inspected receiver uses the native window manager's closing
routine; it does not toggle water mode. Delivery is restricted to registered receivers in
`com.oplus.wearable.sysui`, with at most 16 requests in three seconds. The app sends no
direct MCU commands. Firmware restrictions, broadcast delivery and closing before screen-on
still require on-watch verification. The visible native-button fallback remains available.

The app relies on vendor-specific behavior, so firmware changes may affect it. It cannot guarantee recovery from a frozen system greeting.

## Diagnostics

- **Build diagnostics ZIP** saves recent logs and app/device information for troubleshooting.
- **Clear diagnostics** removes saved logs and the ZIP without changing your settings.

Copy the ZIP to your computer using the [ADB export instructions](docs/GUIDE.md#export-diagnostics). Export requires a debug APK. Logs may contain personal information from system UI text; review them before sharing.

For a bug report, include your watch model, firmware, app version and what happened.

## Build from source

To build your own APK, download this repository (**Code → Download ZIP**), extract it, and open the project folder in **Android Studio**. Let Gradle sync, then build a **debug APK**.

Output: `app/build/outputs/apk/debug/app-debug.apk`. Install it using either method in [Installation](#installation).

[Build requirements and tests](docs/GUIDE.md) · [Protocol notes](docs/PROTOCOL.md)
