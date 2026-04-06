# Issue: SensorTile GATT UUID Mismatch (Resolved)

## Status
- **Date**: 2026-04-06
- **Device**: SensorTile.box PRO
- **Firmware**: DATALOG2 v3.2.0 ("HSD2v32")
- **Status**: Fixed

## Symptom
When attempting to connect to the SensorTile from the Controller screen, the UI would show an error:
`PnPL characteristic not found - check logcat for correct UUID`

## Root Cause
The `SensorTileService.kt` was initially configured with UUIDs from the BlueST v1 SDK which did not match the actual services advertised by the DATALOG2 v3.2.0 firmware.

### Initial (Incorrect) UUIDs:
- **Service**: `00000000-0001-11e1-9ab4-0002a5d5c51b`
- **PnPL Char**: `00000002-0001-11e1-ac8b-0002a5d5c51b`

### Actual (Verified) UUIDs from Logcat:
```
2026-04-06 00:57:36.135 ... D  SERVICE: 00000000-000e-11e1-9ab4-0002a5d5c51b
2026-04-06 00:57:36.135 ... D    CHAR: 00000001-000e-11e1-ac36-0002a5d5c51b  props=0x1e
2026-04-06 00:57:36.135 ... D    CHAR: 00000002-000e-11e1-ac36-0002a5d5c51b  props=0x12
```

The DATALOG2 v3.x firmware uses service `000e` and characteristic `0001` (with properties `0x1e`: READ | WRITE | WRITE_NO_RESPONSE | NOTIFY) for its PnPL command interface.

## Resolution
Updated `SensorTileService.kt` with the verified UUIDs:
```kotlin
private val PNPL_SERVICE_UUID    = UUID.fromString("00000000-000e-11e1-9ab4-0002a5d5c51b")
private val PNPL_WRITE_CHAR_UUID = UUID.fromString("00000001-000e-11e1-ac36-0002a5d5c51b")
```

## Side Effect: "HSD2v32" in Worker Device List
The SensorTile.box PRO is a Dual-Mode device (BLE + Bluetooth Classic). The `BluetoothSyncService` (phone-to-phone sync) scans for Bluetooth Classic devices to find potential Worker phones. Because the SensorTile also advertises itself via Bluetooth Classic, it appears in the "Select worker device" list (often with "Pair Required").

This is expected behavior for Dual-Mode hardware but may be confusing for users. The Controller should always use the "Connect SensorTile" button specifically designed for it, and select only other Android phones for "Bluetooth Sync".

## Phone-to-Phone Sync Note
Logs showed an initial `java.io.IOException: bt socket closed` for the Pixel 8. This appears to be a transient Bluetooth connection failure (possibly a race condition or distance issue). Subsequent attempts were successful:
`Clock sync done for Pixel 8: offset=-470305798ms`
No further action needed unless this becomes frequent.
