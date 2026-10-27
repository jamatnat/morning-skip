# MorningSkip

Automatically dismiss the **“Good morning” greeting on OnePlus Watch 3**, including its afternoon and evening variants.

- Runs through an Accessibility service; no need to keep the app screen open.
- One switch to turn automatic closing on or off.
- Local diagnostics for troubleshooting. No root, screenshots, analytics or Internet access.

Developed on OnePlus Watch 3. Other devices and firmware versions are untested; version 1.0 still needs on-watch validation.

## Build and install

**Download the APK:** open [Releases](https://github.com/jamatnat/morning-skip/releases/latest) and download **MorningSkip.apk** under **Assets**. Install it on the watch using ADB; Android Studio is not required.

**Android Studio:** Download this repository (**Code → Download ZIP**), extract it, and open the project folder in **Android Studio**. Let Gradle sync, then build a **debug APK**.

Install `app/build/outputs/apk/debug/app-debug.apk` on the watch using ADB. See the [build and installation guide](docs/GUIDE.md#build) for requirements, pairing and commands.

## Enable

1. Open MorningSkip and tap **Open Accessibility**.
2. Enable the **MorningSkip** service in watch settings.
3. Return to the app and wait for **Service: connected**.
4. Set **Good Morning Auto-close: ON**.

You can now leave the app. Set Auto-close to **OFF** to stop closing greetings, or disable the Accessibility service to stop logging as well.

## How it works

MorningSkip asks the watch controller to dismiss a detected greeting. If it remains visible, the app tries its Close button, with limited retries. It targets the OnePlus greeting, not other notifications or alarms.

The app relies on vendor-specific behavior, so firmware changes may affect it. It cannot guarantee recovery from a frozen system greeting.

## Diagnostics

- **Build diagnostics ZIP** saves recent logs and app/device information for troubleshooting.
- **Clear diagnostics** removes saved logs and the ZIP without changing your settings.

Copy the ZIP to your computer using the [ADB export instructions](docs/GUIDE.md#export-diagnostics). Export requires a debug APK. Logs may contain personal information from system UI text; review them before sharing.

For a bug report, include your watch model, firmware, app version and what happened.

## Development

[Build requirements and tests](docs/GUIDE.md) · [Protocol notes](docs/PROTOCOL.md)
