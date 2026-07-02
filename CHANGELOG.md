# Changelog

All notable changes to What’s Up, Buds? are documented here.

## [1.5.0] - 2026-07-02

### Added

- "Liquid battery" wave in the notification: each earbud and the case shows a
  wave whose fill height tracks the charge level. It's rendered to a bitmap
  (notifications can't host custom-drawing views) and updates on every reading.
- System-driven connection tracking. The receiver now also listens to the
  A2DP/HEADSET profile connection-state changes — not just the ACL link — and
  updates the notification directly. Connect/disconnect is now reflected
  reliably and in real time, even when the screen is locked or the OS has
  frozen/killed the background service.

### Changed

- Tailored collapsed vs expanded layouts:
  - Collapsed: compact, minimal rounded-rectangle pills showing just the icon
    and percentage on one line.
  - Expanded: larger cards with the icon, a large percentage, and
    LEFT / RIGHT / CASE labels, under a *Connected* status line.
- Battery color now appears only when it matters: a healthy battery uses a
  neutral, understated wave; amber at 30% or below; red at 15% or below.
  Charging brightens the wave.
- Connection tracking works within normal power management — no wake lock and
  no battery-optimization exemption are required.

### Removed

- The "ignore battery optimization / unrestricted battery" request and its
  permission. It was flagged by Play policy and is no longer needed now that
  connection state is driven by system Bluetooth broadcasts.
- Unused low/medium warning-color pill and card backgrounds, and the standalone
  charging-bolt icon (charging is now shown by tinting the wave).

### Fixed

- The notification no longer risks showing a stale battery level after a
  disconnect that happens while the screen is locked; the disconnected state is
  posted directly from the system-woken receiver.

## [1.0.2] - 2026-07-02

> Skipped for release. These changes were rolled into 1.5.0, which ships with
> further improvements.

### Added

- Real-time disconnect detection using the Bluetooth A2DP/HEADSET profile state
  and a connection-state receiver, so the notification switches to
  *Disconnected* the instant the earbuds sleep or the case lid closes, instead
  of waiting for the ACL link to time out.
- Automatic reconnection when another app (for example Huawei AI Life)
  temporarily holds the exclusive SPP channel: the last reading is kept and the
  connection is re-established once the channel is released.
- Custom left earbud, right earbud, and charging-case glyphs in the
  notification pills and expanded cards.
- Diagnostic logging of the connection path, the battery request bytes, and
  received packets.

### Changed

- Redesigned the notification: an inline three-pill collapsed layout and larger
  equal-width battery cards when expanded, with a *Connected* status line, a
  monochrome charging bolt, and soft neutral-gray pills. Light and dark.
- Warning colors now trigger later and more meaningfully — amber at 30% or below
  and red at 15% or below (previously 50% and 20%).
- The battery request now includes the OpenFreeBuds-style empty TLV parameters
  so FreeBuds SE 2 reliably responds.

### Fixed

- The notification is now non-dismissible while connected and becomes swipeable
  once disconnected (the disconnected state is detached from the foreground
  service before being re-posted).
- Fixed the notification getting stuck at *Connecting…* when a duplicate
  connection broadcast arrived while already connected.
- Fixed the notification continuing to show battery percentages after the
  earbuds disconnected, caused by worker threads accumulating across repeated
  connect/disconnect cycles.
- Removed a duplicate color resource that broke the build.

## [1.0.1] - 2026-07-02

> Skipped for release. These changes were rolled into 1.5.0, which ships with
> further improvements.

### Added

- Low-power 60-second fallback polling while retaining immediate device-push
  updates. The poll reuses the existing Bluetooth connection, sleeps between
  requests, and stops on disconnect.

### Fixed

- Prevented the notification from remaining at *Connecting…* indefinitely by
  limiting each RFCOMM connection attempt to 12 seconds.
- Added battery-request retries after 5 and 10 seconds during startup.
- Added a clear *Connected · No battery data yet* status when RFCOMM connects
  successfully but no valid battery packet is received.

## [1.0.0] - 2026-07-02

First public release.

### Added

- Automatic foreground-service startup when Android reports an audio-class
  Bluetooth device connection.
- Huawei SPP/RFCOMM battery communication for HUAWEI FreeBuds SE 2.
- Left earbud, right earbud, and charging-case battery status.
- Compact notification pills and a larger expanded battery-card layout.
- Charging indicators and warning-colored pill backgrounds for medium and low
  battery levels.
- Persistent device naming so the disconnected notification retains the last
  connected Bluetooth name.
- Light and dark notification styling.
- Protocol, CRC, parsing, and packet-framing unit tests.

### Notes

- Hardware support is currently focused on HUAWEI FreeBuds SE 2.
- Other Huawei and Honor models may work but have not been verified.
- This remains an independent, hardware-dependent project and is not affiliated
  with Huawei, Honor, or OpenFreeBuds.
