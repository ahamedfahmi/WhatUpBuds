# What’s Up, Buds?

An experimental Android app that shows Huawei FreeBuds battery information in a
quiet foreground-service notification. The current implementation is intended
for **HUAWEI FreeBuds SE 2**.

> Status: early and hardware-dependent. The Android project builds and the
> protocol/framing code has unit tests, but connection behavior and battery
> fields should still be verified on real FreeBuds SE 2 hardware.

## Protocol credit and authorship

The Huawei SPP packet framing, CRC, battery command IDs, battery TLV layout, and
FreeBuds SE 2 RFCOMM information used as references for this project were
informed by [OpenFreeBuds](https://github.com/melianmiko/OpenFreebuds), created
by [MelianMiko](https://github.com/melianmiko).

OpenFreeBuds did the important reverse-engineering and documentation work. The
Android/Kotlin implementation in this repository was written independently and
is not a direct port of the OpenFreeBuds Python application.

The base app was written by **Ahamed Fahmi**. AI tools were later used to help
refactor code, improve documentation, create original icon assets, and fix some
bugs.

This project is not affiliated with or endorsed by Huawei, Honor, or the
OpenFreeBuds project.

> OpenFreeBuds is licensed under GPLv3. If OpenFreeBuds source code is copied or
> adapted into this project, its copyright notices and GPLv3 requirements must
> be followed. This repository does not currently include its own `LICENSE`
> file; one should be added before a public release.

## Current features

- Starts automatically when Android reports that an audio-class Bluetooth
  device has connected.
- Opens an RFCOMM connection using the standard SPP UUID, with channel `1` as a
  fallback.
- Sends one battery request after connecting, then waits for responses and
  unsolicited battery updates.
- Displays left, right, and case battery percentages in one quiet notification.
- Displays charging indicators using the app’s current interpretation of
  parameter `0x03`.
- Keeps a dismissible *Disconnected* notification after the service stops.
- Uses a small monochrome notification icon and a separate original adaptive
  launcher icon.
- Has no widget, account, analytics, internet request, periodic polling loop, or
  wake lock.

## How it works

```text
Bluetooth ACL_CONNECTED for an audio-class device
                         │
                         ▼
              BtConnectionReceiver
                         │
                         ▼
                  BudsService
             foreground notification
                         │
            RFCOMM using standard SPP UUID
             fallback: RFCOMM channel 1
                         │
            send command 0x0108, no params
                         │
                         ▼
                   PacketFramer
         rebuild complete packets from the stream
                         │
                         ▼
            HuaweiProtocol.parseBattery
                         │
                         ▼
            update the same notification
```

`BtConnectionReceiver` is registered in the manifest for
`ACL_CONNECTED` and `ACL_DISCONNECTED`. On an audio-device connection it starts
`BudsService` and passes the `BluetoothDevice`. The service owns both the
RFCOMM socket and the foreground notification.

The service first tries
`00001101-0000-1000-8000-00805f9b34fb`, the standard Serial Port Profile UUID.
If that connection fails, it uses Android’s hidden RFCOMM method as a fallback
and tries channel `1`, which is the channel documented by OpenFreeBuds for
FreeBuds SE 2.

## Protocol implementation

The project implements Huawei’s SPP packet structure as follows:

| Offset | Length | Field |
|--------|--------|-------|
| `0` | 1 | Start byte `0x5A` |
| `1` | 2 | Big-endian data length |
| `3` | 1 | Constant `0x00` |
| `4` | 2 | Big-endian command ID |
| `6` | N | TLV parameters: type, length, value |
| `6+N` | 2 | Big-endian CRC16/XMODEM |

- CRC: polynomial `0x1021`, initial value `0x0000`, no reflection and no
  final XOR.
- Battery request/response command: `0x0108`.
- Unsolicited battery notification command: `0x0127`.
- Parameter `0x02`: the first three bytes are currently parsed as left, right,
  and case battery percentages.
- Parameter `0x03`: the first three bytes are currently interpreted as separate
  left, right, and case charging flags.

### Differences from OpenFreeBuds

This is not an exact copy of the OpenFreeBuds battery handler:

- OpenFreeBuds requests parameter types `1`, `2`, and `3` using empty TLVs. This
  app currently sends command `0x0108` with no parameters.
- OpenFreeBuds treats parameter `0x03` as a general charging indication. This
  app currently assumes three per-component charging bytes. That mapping still
  needs real-hardware confirmation.
- OpenFreeBuds connects directly to RFCOMM channel `1` for FreeBuds SE 2. This
  app tries the standard SPP service UUID first and channel `1` only as a
  fallback.

`PacketFramer` scans for `0x5A`, reads the two-byte length, and then reads the
remaining body and CRC. It accepts payload lengths up to 1024 bytes. CRC
mismatches are logged, but the current service still attempts a lenient parse.

## Notification behavior

- Notification channel importance is `LOW`: no sound and no heads-up alert.
- Visibility is `PUBLIC`, allowing the status to appear on the lock screen.
- The title uses the complete Bluetooth device name reported by Android. It is
  not shortened or hardcoded to a particular Huawei model.
- Each percentage uses the normal notification text color at 50–100%, orange
  at 21–49%, and red at 0–20%. Theme-specific warning shades keep the text
  readable in light and dark mode. A ⚡ is added while charging.
- Battery readings use Android's decorated custom notification content so OEM
  system templates cannot discard the individual percentage colors.
- Example body: `L 82%  ·  R 39% ⚡  ·  Case 15%`.
- Tapping the notification opens the app’s permission/status screen.
- The compact system template is used without an expandable big-text layout,
  large image, custom background, or action buttons.
- `setOnlyAlertOnce(true)` prevents repeated alerts on battery updates.
- Updates are explicitly silent and remain local to the phone.
- The notification is ongoing while connected.
- After disconnecting, the service stops and leaves a dismissible
  *Disconnected* notification.
- Android’s required small status icon is used; no large product image is added
  to the notification.

## Permissions

- `BLUETOOTH_CONNECT` on Android 12/API 31 and newer.
- `POST_NOTIFICATIONS` on Android 13/API 33 and newer.
- Legacy `BLUETOOTH` and `BLUETOOTH_ADMIN` permissions through API 30.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`.

`MainActivity` only displays a short background-operation message and requests
the runtime permissions required for the current Android version.

## Known limitations

- A connection attempt is triggered for any audio-class Bluetooth device, not
  only FreeBuds SE 2.
- The disconnect receiver does not currently verify that the disconnected
  device is the one owned by `BudsService`. Disconnecting another Bluetooth
  device may therefore stop the service.
- The battery request and charging-flag interpretation are not identical to
  OpenFreeBuds and need hardware validation.
- Unsupported earbuds may remain at *Connecting…* briefly or fail the RFCOMM
  connection.
- There are unit tests for protocol construction, parsing, CRC, and stream
  framing, but no automated Bluetooth hardware tests.

## Contributing

Contributions are welcome—especially contributions that add support for more
Huawei and Honor earbud models. Bug reports, hardware test results,
documentation improvements, and pull requests are also appreciated.

Help is especially useful for:

- Adding and testing support for additional Huawei/Honor earbuds.
- Testing existing support on real FreeBuds SE 2 earbuds and different Android
  versions.
- Verifying the battery-request parameters and charging-state bytes.
- Fixing Bluetooth device filtering and disconnect handling.
- Adding protocol tests without including private or copyrighted data.

When adding or requesting support for a device, please include:

- The exact earbud model name.
- The phone model and Android version used for testing.
- The RFCOMM channel or protocol details, if known.
- Relevant filtered logs or sanitized packet samples.
- Which features were tested, such as battery level, charging state, and
  connection/disconnection behavior.

Do not include firmware, personal Bluetooth information, or copyrighted/private
data. Keep the OpenFreeBuds attribution when contributing protocol-related
changes.

## Build

Project configuration:

- Minimum SDK: 26
- Compile/target SDK: 34
- Android Gradle Plugin: 8.5.0
- Kotlin: 1.9.24
- Gradle: 8.7
- Java/JDK: 17

The easiest option is to open the project in Android Studio and select **Run**
or **Build APK**.

This checkout currently contains `gradle-wrapper.properties`, but not the
complete wrapper scripts and JAR. If Gradle is installed locally, generate them
once:

```powershell
gradle wrapper --gradle-version 8.7
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

On macOS or Linux, use `./gradlew` after generating the wrapper.

## Project layout

```text
app/src/main/java/com/whatsupbuds/
  MainActivity.kt            permission screen
  BtConnectionReceiver.kt    Bluetooth connect/disconnect receiver
  BudsService.kt             RFCOMM connection and notification
  HuaweiProtocol.kt          packet construction and battery parsing
  Crc16.kt                   CRC16/XMODEM
  PacketFramer.kt            RFCOMM stream framing

app/src/main/res/
  drawable/ic_stat_buds.xml
  drawable/ic_launcher_foreground.xml
  mipmap-anydpi-v26/ic_launcher.xml

app/src/test/java/com/whatsupbuds/
  ProtocolTest.kt
```
