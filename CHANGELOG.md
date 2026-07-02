# Changelog

All notable changes to What’s Up, Buds? are documented here.

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
