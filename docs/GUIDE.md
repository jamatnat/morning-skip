# Build, install and diagnostics

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

## GitHub Actions setup

The **Android APK** workflow runs manually or when app/build/test files change on `main`. It runs policy checks, builds a debug APK and publishes a new app version to **Releases**, with a SHA-256 checksum. Actions artifacts are also kept for 30 days.

A release is created once per `versionName` in `app/build.gradle`. Existing releases and their APKs are left unchanged. For the next release, increase both `versionName` and `versionCode`, then push to `main`.

Use a permanent signing key so APKs from subsequent runs can update one another. To keep compatibility with your Android Studio debug build, use its existing default debug keystore (alias `androiddebugkey`, store/key passwords `android`). A custom signing configuration requires corresponding workflow changes.

CI restores the key to a temporary file and passes its path explicitly through `MORNINGSKIP_KEYSTORE`. Before publishing, it checks that the APK certificate matches the certificate in that file. Local Android Studio builds continue to use the normal local debug key.

**Updating from 1.0 or 1.0.1:** those public APKs have different signing certificates. If installation reports a signature conflict, uninstall the old copy once, install 1.0.2 or later, then re-enable Accessibility and Auto-close. Uninstalling removes app settings and logs.

1. Build the app once in Android Studio so the key exists. In PowerShell, copy its Base64 representation to your clipboard:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard
   ```

2. Open repository **Settings → Secrets and variables → Actions → New repository secret**. Name it `ANDROID_DEBUG_KEYSTORE_BASE64`, paste the clipboard into **Secret**, and save. Keep the key private and back up the original file.
3. Open **Actions → Android APK → Run workflow**, select `main`, and run it. If a previous run stopped at **Check signing secret**, you can also use **Re-run all jobs** after adding the secret.
4. After a successful run, open **Releases** and download **MorningSkip.apk** under **Assets**. Install it with ADB:

   ```text
   adb -s WATCH_IP:CONNECTION_PORT install -r MorningSkip.apk
   ```

Release APKs can be downloaded without signing in. Intermediate builds remain available under the run’s **Artifacts** as **MorningSkip-apk-…**; those downloads require signing in. These are debug builds, so the ADB diagnostics export below remains available. A missing signing secret stops the workflow instead of silently generating a different key. Do not replace the secret with a new key unless you intend to change signing identity.

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

## Export diagnostics

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

## Development checks

Pure Java policy checks need a JDK, but no watch or Android SDK:

```powershell
.\tests\run-tests.ps1
```

```sh
sh tests/run-tests.sh
```

The suite covers 144 assertions/checks for UI dismissal, controller decoding/closing, and recovery limits. See [protocol notes](PROTOCOL.md) for the vendor message mapping.
