# Battery Monitor

A small, native Android app for monitoring multiple JBD / Jiabaida / Xiaoxiang / Overkill Solar-compatible battery-management systems over Bluetooth Low Energy.

## First-version features

- Finds nearby JBD BLE modules and supports simultaneous connections to multiple BMS devices.
- Remembers successfully connected devices and their last telemetry/settings snapshot across app restarts.
- Shows last-connected and last-updated timestamps and allows direct reconnect without scanning first.
- Keeps BLE connections alive while switching apps, then disconnects after a configurable background timeout (5 seconds to 30 minutes, or never; default 10 seconds).
- Provides a dedicated app Settings screen, opened from the cogwheel in the main toolbar.
- Checks `joeke.dev` for updates from Settings and can securely download and hand a newer APK to Android's system installer.
- Optionally uploads each connected BMS reading after the first complete reading on connection and about every 30 seconds to a configured HTTPS endpoint, authenticated with a masked API key (`X-Api-Key`). Uploading is disabled by default. Optional background uploads reconnect to saved BMSes about every 30 minutes.
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

## Background uploads

In Settings, enable **Store data on server**, save the HTTPS URL and API key, then enable **Background uploads** on each Android device that should participate. Connect to each BMS once to save it first.

With background uploads enabled and the server configured, leaving the app disconnects immediately (overriding the Background disconnect timeout). WorkManager attempts an upload cycle about every 30 minutes when a network is available. Each cycle visits saved, previously connected BMSes in random order, reads fresh basic telemetry and cell voltages, releases Bluetooth, and uploads the snapshot. It does not read configuration registers. A busy/unreachable BMS has a 45-second connection/read limit; a whole cycle has a four-minute limit. Failed attempts are skipped until the next period. Small random delays reduce collisions between Android devices; they do not coordinate ownership across devices.

Opening the app releases any background connection immediately. Turning off background uploads or server uploads cancels the scheduled work. Background uploads default to off independently on each installation.

Android may delay work during Doze, battery saving, or manufacturer background restrictions, so this is not an exact 30-minute timer. Force-stopping the app prevents jobs until it is opened again. Bluetooth and its permission must remain enabled; out-of-range or already occupied BMSes cannot be read. Multiple phones can still upload the same BMS at different times using their existing sender identifiers.

Device validation: enable the option with two saved BMSes, leave the app, and check the server for fresh readings from each sender after a scheduled cycle. Repeat with one BMS occupied or out of range, Bluetooth off, and by opening the app during a background read. Confirm connections are released after success, timeout, cancellation, and returning to the foreground. JVM tests cover the background telemetry-only protocol sequence; radio and OS scheduling behavior need real devices.

## App updates

Release builds check `https://joeke.dev/android-apps/battery-monitor/latest.json`. The GitHub repository may be private: the app only talks to the public HTTPS update directory and never contains GitHub or server credentials.

The `Publish release APK` GitHub Actions workflow runs when a new `releases/*.apk` file is pushed to `main` (and can also be started manually). It selects the highest versioned `Battery-Monitor-<version>.apk` file, creates `latest.json` and a SHA-256 file, then uploads them with `appleboy/scp-action`. The APK and checksum are uploaded before the manifest, so clients cannot discover an APK before its upload has completed.

The workflow mirrors the existing `joeke.dev` deployment configuration: it connects to `188.245.189.168` and publishes to `/home/joeke_dev/htdocs/public/android-apps/battery-monitor/`. Before running it, add these two repository secrets under **Settings → Secrets and variables → Actions**:

| Name | Value |
| --- | --- |
| `hetzner_ssh_username` | The same SSH username used by the `joeke.dev` repository |
| `hetzner_ssh_password` | The same SSH password used by the `joeke.dev` repository |

The SSH account needs permission to create the target directory and replace files inside it.

To publish a release, increment both `versionCode` and `versionName` in `app/build.gradle.kts`, build and test the signed APK locally, and copy it into `releases/` using the versioned filename:

```shell
./gradlew testReleaseUnitTest assembleRelease
cp app/build/outputs/apk/release/app-release.apk releases/Battery-Monitor-0.5.2.apk
```

Commit the source changes and APK, then push them to `main`. Adding the APK triggers the workflow; editing or deleting an existing APK does not publish anything. A manual workflow run republishes the highest versioned APK on the selected branch. GitHub Releases and tags are not required.

To build against a different update server locally, override the HTTPS manifest URL:

```shell
./gradlew -PUPDATE_MANIFEST_URL=https://example.com/battery-monitor/latest.json assembleRelease
```

The manifest is a small static file, so the server does not need to expose a browsable folder:

```json
{
  "versionName": "0.5.2",
  "apkUrl": "https://example.com/battery-monitor/Battery-Monitor-0.5.2.apk",
  "releasePageUrl": "https://example.com/battery-monitor/"
}
```

Before offering the APK to Android, the app verifies that its package name is `com.jbd.bmsmonitor`, its version code is newer, and its signing certificate matches the installed app. The JSON manifest and APK must be publicly readable over HTTPS, but directory listing can remain disabled. Android 8.0+ also requires the user to explicitly allow Battery Monitor as an APK installation source; the app opens that system setting when needed.

## Device notes

- Most JBD modules allow only one central connection. Fully close other BMS apps before connecting.
- Some clones do not expose the standard `FF00/FF01/FF02` service or password-protect factory mode. Live telemetry will still work when configuration access is unavailable.
- Foreground monitoring runs while the app process is active. Optional background uploads use WorkManager for short BLE sessions, including after process restart or reboot.
- Closing the app releases all GATT connections; saved devices and snapshots remain available offline.

## Protocol references

- [Overkill Solar BLE protocol notes](https://github.com/FurTrader/OverkillSolarBMS/blob/master/Comm_Protocol_Documentation/BLE%20_bluetooth_protocol.md)
- [JBD serial register map](https://github.com/ieb/N2KLifePo4/blob/main/JBD-BMS-SERIAL-INTERFACE.md)
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
