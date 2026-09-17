# Battery Monitor

A small, native Android app for monitoring multiple JBD / Jiabaida / Xiaoxiang / Overkill Solar-compatible battery-management systems over Bluetooth Low Energy.

## First-version features

- Finds nearby JBD BLE modules and supports simultaneous connections to multiple BMS devices.
- Remembers successfully connected devices and their last telemetry/settings snapshot across app restarts.
- Shows last-connected and last-updated timestamps and allows direct reconnect without scanning first.
- Shows state of charge, pack voltage, signed current, calculated power, remaining/full capacity, cycle count, and charge/discharge MOS state.
- Shows every cell voltage, lowest/highest/average cell, balancing state, and pack cell delta.
- Shows all reported NTC temperature sensors.
- Reads common configuration values: cell count, capacity, 100%/0% cell voltage, protection thresholds, balancing thresholds, and temperature cutoffs.
- Has no setting-editing or EEPROM-write UI/code paths.

The JBD BLE UART bridge uses service `FF00`, notifications on `FF01`, and requests on `FF02`. Configuration registers require a temporary factory-mode session. The app enters that volatile mode only to issue read commands and exits using `00 00`, which does not save settings or reset counters.

## Build

Open the project in Android Studio (JDK 17) and run the `app` configuration on an Android 8.0+ phone with BLE. Alternatively:

```shell
./gradlew test assembleDebug
```

On Android 12+, grant the Nearby devices permission. On Android 8–11, Android requires location permission for BLE scanning. The app does not derive or store location.

## Device notes

- Most JBD modules allow only one central connection. Fully close other BMS apps before connecting.
- Some clones do not expose the standard `FF00/FF01/FF02` service or password-protect factory mode. Live telemetry will still work when configuration access is unavailable.
- Polling and connections run while the app process is active. A persistent foreground monitoring service is intentionally outside this first version.
- Closing the app releases all GATT connections; saved devices and snapshots remain available offline.

## Protocol references

- [Overkill Solar BLE protocol notes](https://github.com/FurTrader/OverkillSolarBMS/blob/master/Comm_Protocol_Documentation/BLE%20_bluetooth_protocol.md)
- [JBD serial register map](https://github.com/ieb/N2KLifePo4/blob/main/JBD-BMS-SERIAL-INTERFACE.md)
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
