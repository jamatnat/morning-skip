param([string]$Device, [switch]$Stop)
$ErrorActionPreference = 'Stop'
$adbArgs = @()
if ($Device) { $adbArgs = @('-s', $Device) }
else {
    $devices = @(adb devices | Select-String '^\S+\s+device$')
    if ($LASTEXITCODE -ne 0 -or $devices.Count -ne 1) {
        throw 'Connect the watch first: adb pair IP:PAIRING_PORT (if needed), then adb connect IP:CONNECTION_PORT. Use -Device IP:PORT when multiple devices are connected.'
    }
}

$paths = @(adb @adbArgs shell pm path io.github.jamatnat.morningskip)
if ($LASTEXITCODE -ne 0 -or $paths.Count -eq 0) { throw 'Install MorningSkip 1.0.4 or later first.' }
$apkPath = ($paths[0] -replace '^package:', '').Trim()
if ($apkPath -notmatch '^/data/app/[A-Za-z0-9_./~+=-]+/base\.apk$') {
    throw 'Unexpected installed APK path; helper was not started.'
}
$entry = "CLASSPATH='$apkPath' app_process /system/bin io.github.jamatnat.morningskip.ShellRecovery"

function Test-HelperReady {
    $savedPreference = $ErrorActionPreference
    try {
        # A missing helper is expected on first setup, including in Windows PowerShell 5.1.
        $ErrorActionPreference = 'Continue'
        $lines = @(adb @adbArgs shell "$entry --status" 2>&1)
        return ($LASTEXITCODE -eq 0 -and ($lines -join "`n") -match 'AVAILABLE 1')
    } finally { $ErrorActionPreference = $savedPreference }
}

# Only the existing fixed-purpose helper is stopped, never the system UI during setup.
if (Test-HelperReady) {
    adb @adbArgs shell "$entry --stop"
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop the previous recovery helper.' }
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        if (-not (Test-HelperReady)) { break }
        Start-Sleep -Milliseconds 200
    }
}
if ($Stop) { Write-Host 'Recovery helper stopped.'; return }

adb @adbArgs shell "umask 077; CLASSPATH='$apkPath' nohup app_process /system/bin io.github.jamatnat.morningskip.ShellRecovery > /data/local/tmp/morningskip-recovery.log 2>&1 < /dev/null &"
if ($LASTEXITCODE -ne 0) { throw 'Could not start the helper.' }
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    Start-Sleep -Milliseconds 500
    if (Test-HelperReady) {
        Write-Host 'Recovery helper ready. Enable Auto-close in MorningSkip.'
        Write-Host 'Run this script again after restarting the watch or updating the APK.'
        return
    }
}
adb @adbArgs shell cat /data/local/tmp/morningskip-recovery.log
throw 'Helper did not become ready. Send the output above; automatic recovery is unavailable.'
