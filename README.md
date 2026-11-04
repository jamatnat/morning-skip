# MorningSkip

Version **1.0.4** closes a ready native greeting while the screen is OFF when firmware
exposes it. The service requires a fresh attached greeting tree; it never sends an
independent early controller dismissal. If OFF-screen readiness is unavailable, the
native visible-button fallback closes the greeting after wake.

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

MorningSkip first waits for an attached native greeting window while the screen is OFF. It refreshes the known greeting layout and Close button before a bounded native click. The system handler removes its own window and synchronizes the controller. If no ready window can be accessed while OFF, the app waits for a visible, unobscured Close button after wake. It sends no independent controller query or dismissal commands. It targets the OnePlus greeting, not other notifications or alarms.

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
