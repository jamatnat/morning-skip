# MorningSkip

Automatically dismiss the OnePlus watch's “Good morning” greeting.

MorningSkip 1.0 is the public version of the personal GoodMorningProbe project. It uses the dismissal logic from development version 5.8, with a simpler interface and no screenshot capture tools.

**Device scope:** development and field testing have been on **OnePlus Watch 3** (`OPWWE251`, Android API 34). Other watches and firmware versions are untested. The public 1.0 build still needs on-watch validation; it does not guarantee removal of a frozen system greeting.

## Build

Open this repository's root folder in Android Studio, let Gradle sync, and build a **debug APK**. The project uses JDK 17, Android SDK 35, Android Gradle Plugin 8.7.3 and the included Gradle 8.9 wrapper.

Or, with JDK 17 and Android SDK 35 configured:

```powershell
.\gradlew.bat assembleDebug
```

On Linux/macOS:

```sh
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Android's normal local debug key is used. No signing key is included. Keep the same local key for future updates; APKs built with a different key cannot replace an installed copy without uninstalling it first.

## Install over wireless ADB

Enable Developer options and Wireless debugging on the watch. Keep the computer and watch on the same Wi-Fi network.

1. Open **Pair new device** on the watch. Use the address and **pairing port** shown there, then enter its pairing code:

   ```text
   adb pair WATCH_IP:PAIRING_PORT
   ```

2. Return to the main **Wireless debugging** screen. Use its **connection port**, which is different from the pairing port:

   ```text
   adb connect WATCH_IP:CONNECTION_PORT
   adb devices
   ```

3. Install the APK from the repository folder:

   ```text
   adb -s WATCH_IP:CONNECTION_PORT install -r app/build/outputs/apk/debug/app-debug.apk
   ```

Replace the placeholders with the values currently displayed on your watch. Ports can change. Pairing is normally needed only once; reconnect using the current connection port.

**Coming from GoodMorningProbe 5.x?** Disable its Accessibility service before enabling MorningSkip. The public app has a new package ID (`io.github.jamatnat.morningskip`) and installs separately. Settings and logs are not migrated.

## Enable

1. Open MorningSkip on the watch.
2. Tap **Open Accessibility**, find **MorningSkip**, and enable its service.
3. Return to the app and wait for **Service: connected**.
4. Set **Good Morning Auto-close: ON**.

You can leave the app screen. The Accessibility service handles detection and closing. To stop automatic closing, set Auto-close to **OFF**. To stop the service and its diagnostic logging too, disable MorningSkip in Accessibility.

The service status reports the Accessibility connection, not proof that a particular greeting has closed.

## How it works

The service listens for OnePlus greeting-controller messages and asks the controller to dismiss a freshly reported greeting. It also checks the visible system greeting and can activate its Close button, with a limited tap fallback.

The code keeps controller reports separate from visible-screen verification. A controller “hidden” report does not prove that Android has removed the greeting. If the visible greeting persists, bounded retries and a rate-limited controller query help avoid endless clicking or sending commands.

The same system greeting layout can say “Good afternoon” or “Good evening”; detection by its view ID does not depend on that wording. This is not a general popup blocker or an alarm-dismissal app.

## Diagnostics

- **Build diagnostics ZIP** snapshots recent logs and app/device information into the app's private `files/diagnostics.zip`. It does not automatically download or send it.
- **Clear diagnostics** deletes saved logs and that ZIP, without changing settings. A connected service can immediately begin new logs.

After the app shows **diagnostics.zip ready**, copy the file from a **debug build**. With **PowerShell 7.4 or newer**, in the directory where you want to save it:

```powershell
adb -s WATCH_IP:CONNECTION_PORT exec-out run-as io.github.jamatnat.morningskip cat files/diagnostics.zip > .\morning-skip-diagnostics.zip
```

With Windows PowerShell 5.1 or an older PowerShell version, run the redirect through `cmd.exe` to preserve ZIP bytes:

```powershell
cmd /c "adb -s WATCH_IP:CONNECTION_PORT exec-out run-as io.github.jamatnat.morningskip cat files/diagnostics.zip > morning-skip-diagnostics.zip"
```

On Linux/macOS:

```sh
adb -s WATCH_IP:CONNECTION_PORT exec-out run-as io.github.jamatnat.morningskip cat files/diagnostics.zip > morning-skip-diagnostics.zip
```

`run-as` requires a debuggable APK. A normal release build cannot use this export method. Rebuild the ZIP after reproducing a problem; an existing ZIP does not update automatically.

When reporting a problem, include the approximate time, watch firmware, app version, what appeared, and what you pressed. Review logs before sharing: they can include system UI text and controller payloads, which may contain personal information. Avoid posting an unreviewed ZIP in a public issue.

## Permissions and local data

- **Accessibility:** reads window information to identify the system greeting and activate its Close button. Gesture access supports the tap fallback.
- **Wake lock:** briefly finishes an already detected closing attempt. Timer callbacks themselves do not wake a suspended CPU.
- Logs are stored in private app storage with rotation (about 10 MiB total for the current and previous event/diagnostic logs, plus an exported ZIP).
- No screenshot capture, Internet permission, analytics, or automatic upload. Root is not required.

## Limitations

The controller interface is vendor-specific and accessed through reflection. Firmware changes can break it. Accessibility detection also depends on system view IDs and window visibility. Compatibility is not established beyond the tested device/firmware history.

Earlier development builds encountered a system greeting that remained frozen even after the app was force-stopped. A watch restart cleared it. Recovery logic is bounded; this app cannot promise to repair a stuck system process. There is no confirmed diagnosis tying that issue to an alarm.

## Development checks

Pure Java policy checks need a JDK, but no watch or Android SDK:

```powershell
.\tests\run-tests.ps1
```

```sh
sh tests/run-tests.sh
```

The suite covers 144 assertions/checks for UI dismissal, controller decoding/closing, and recovery limits. See [protocol notes](docs/PROTOCOL.md) for the vendor message mapping.
