# OnePlus greeting protocol notes

In version 1.0.4 these vendor messages are observed passively.
The service sends neither queries nor dismissals; the mappings below describe the protocol,
not independent commands sent by this app. No verified firmware-fix claim is made.

These notes describe the system app version inspected during development, not a stable public API. Vendor APKs and decompiled vendor source are not included.

The app uses a reflected vendor `McuManager` interface and message class **10**:

| Message | Direction | Meaning used here |
| --- | --- | --- |
| 31 | To controller | Query greeting state; payload `08 01` |
| 32 / 36 | From controller | Greeting state: protobuf int32 `great` field 1, counter field 2 |
| 33 | To controller | Dismiss greeting; payload `08 01` |
| 34 | From controller | Not treated as proof of visual closure |
| 35 | System acknowledgement | Counter acknowledgement; MorningSkip does not send it |

A valid state with `great=1` means shown; `great=0` means hidden. Protobuf scalar defaults apply, but an empty/invalid payload is not accepted as proof of closure. The decoder and policy have regression cases in `tests/McuGreetingTest.java`.

Sending a command, receiving a hidden-state report, and confirming a clear Android UI are distinct events. Controller-hidden state can coexist with a stale Android greeting. `GreetingRecoveryPolicy` tracks that distinction for local diagnostics; this build sends no recovery queries.

Native UI dismissal targets `com.oplus.wearable.sysui`, the `wake_up_root` greeting layout, and the `bt_negative` close control. It checks current visibility and overlapping windows before acting; unknown or blocked observations do not count as successful closure.

No alarm, sleep-tracking, or generic notification commands are sent. The implementation does not change the watch's system settings to disable the greeting at its source.

## Guarded OFF-screen native closing (1.0.4)

A shown MCU state or greeting state-change event only arms a bounded three-second
readiness probe. It does not authorize dismissal. A PARTIAL_WAKE_LOCK finishes the probe
without turning on the screen; the deadline is not extended by repeated events.

The service clears its accessibility cache, refreshes a current SysUI root, requires the
exact `wake_up_root` layout and an enabled/clickable `bt_negative` in the same window,
and checks stable window ID and bounds across fresh samples. Android's accessibility
interaction controller only services node lookups/actions for attached, framework-shown
views and refuses actions for stopped roots. An event's text and controller counter alone
are never readiness evidence.

Only ACTION_CLICK on that exact native button is allowed while the display is OFF and
noninteractive. The vendor callback orders Android-window removal and controller dismissal
inside SysUI. No off-screen coordinate gestures or independent MCU sends are used.
There are at most two attempts; Auto-close OFF, screen-on, disconnect and deadline expiry
cancel the probe and release its node bookmarks and wake lock. A cached node bookmark
is always refreshed and revalidated before acting.

OFF-screen tree availability depends on firmware. Missing or unqueryable roots do not
permit a dismissal. The visible native fallback is retained; a controller-hidden report,
accepted click, missing root or sleeping display is never called proof of visual closure.
This guards against the modelled early-close ordering; full prevention of the reported
rare freeze still requires on-watch evidence. No vendor APK or private device trace is
included in this repository.
