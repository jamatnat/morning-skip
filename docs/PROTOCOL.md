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

A valid state with `great=1` means shown; `great=0` means hidden. Protobuf scalar defaults apply, but an empty/invalid payload is not accepted as proof of closure. The decoder and policy have regression cases in `tests/McuGreetingTest.java`.

Sending a command, receiving a hidden-state report, and confirming a clear Android UI are distinct events. Controller-hidden state can coexist with a stale Android greeting. `GreetingRecoveryPolicy` tracks that distinction and limits recovery queries.

UI fallback targets `com.oplus.wearable.sysui`, the `wake_up_root` greeting layout, and the `bt_negative` close control. It checks current visibility and overlapping windows before acting; unknown or blocked observations do not count as successful closure.

No alarm, sleep-tracking, or generic notification commands are sent. The implementation does not change the watch's system settings to disable the greeting at its source.
