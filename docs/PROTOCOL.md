# OnePlus greeting protocol notes

These notes describe the system app version inspected during development, not a stable public API. Vendor APKs and decompiled vendor source are not included.

The app uses a reflected vendor `McuManager` interface and message class **10**:

| Message | Direction | Meaning used here |
| --- | --- | --- |
| 31 | To controller | Query greeting state; payload `08 01` |
| 32 / 36 | From controller | Greeting state: protobuf int32 `great` field 1, counter field 2 |
| 33 | To controller | Dismiss greeting; payload `08 01` |
| 34 | From controller | Not treated as proof of visual closure |
| 35 | System acknowledgement | Counter acknowledgement; MorningSkip does not send it |

A valid state with `great=1` means shown; `great=0` means hidden. Protobuf scalar defaults apply.
Empty message-32 replies use the protobuf defaults `great=0,cnt=0`.
Version 1.0.4 accepts these only within three seconds of an outstanding successful query,
consuming that expectation once. Empty unsolicited state events, null data and malformed
payloads remain invalid. A decoded hidden state is never proof of visual closure.
The decoder and policy have regression cases in `tests/McuGreetingTest.java` and `tests/StuckGreetingTest.java`.

Sending a command, receiving a hidden-state report, and confirming a clear Android UI are distinct events. Controller-hidden state can coexist with a stale Android greeting. `GreetingRecoveryPolicy` tracks that distinction and limits recovery queries.

UI fallback targets `com.oplus.wearable.sysui`, the `wake_up_root` greeting layout, and the `bt_negative` close control. It checks current visibility and overlapping windows before acting; unknown or blocked observations do not count as successful closure.

No alarm, sleep-tracking, or generic notification commands are sent. The implementation does not change the watch's system settings to disable the greeting at its source.

## Stuck-window recovery

Controller state and the Android window can disagree. A successful command or hidden-state
report must not suppress checking a visible greeting. A normal APK lacks the privilege to
force-stop SysUI, so the optional helper provides only a fixed recovery operation.

Sound/vibration state is recorded in the background for troubleshooting. This does not
establish sound as a cause, and no audio settings are changed.

## Optional shell helper

The normal app cannot force-stop another package. `ShellRecovery`, explicitly launched
as ADB shell uid 2000, exposes only status/stop and the fixed SysUI recovery operation on
an abstract Unix socket. The app verifies the helper's peer uid; the helper verifies the
currently installed MorningSkip uid. Other apps cannot request a restart, and the shell
client itself can only query status or stop the helper. No request carries a command.

Recovery uses a prepare/commit exchange. Before commit the accessibility service refreshes
the same visible, unobscured greeting window with Auto-close enabled and the screen ON.
The helper independently verifies `mCurrentFocus`, `mFocusedApp` and awake screen state
before and after prepare. Missing, blocked or OFF evidence prevents recovery. There is a
10-minute cooldown and a three-attempt 24-hour budget for each helper lifetime. App-side
cooldown also survives service recreation. The helper exits on reboot; restarting it
manually resets its in-memory budget. No interval timer wakes the sleeping CPU.

Before the fixed restart the helper preserves the window dump in a shell-owned local
file. A command result is logged as `RESTART_SENT`, not visual success. The app separately
checks stable clear SysUI evidence. The new IPC/helper mechanism still needs device
validation, including OnePlus SELinux policy and survival after disconnecting ADB.
