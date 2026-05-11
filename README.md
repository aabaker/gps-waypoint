# GPS Waypoint Navigator

An Android navigation app for hiking, cycling, and other outdoor activities.
Load a GPX file, follow the direction arrow to each waypoint, and record your
route (with heart-rate data) to a TCX file.

---

## Features

| Feature | Details |
|---|---|
| **GPX loading** | Supports `<wpt>`, `<rtept>`, and `<trkpt>` elements; loaded via the system file picker (SAF) — no storage permission required |
| **GPS navigation** | Live bearing and distance to the active waypoint; auto-advances at 25 m |
| **Compass arrow** | Rotates in real-time to point toward the target waypoint |
| **Waypoint buttons** | Prev / Next buttons plus waypoint counter (3/12) |
| **BLE heart rate** | Auto-scans for any BLE Heart Rate Service (0x180D) peripheral |
| **TCX recording** | Records path + HR to `Android/data/com.example.gpswaypoint/files/recordings/` |
| **Foreground service** | Navigation and recording continue when the screen is off |
| **Clean shutdown** | Finishes immediately if location permission is denied |

---

## Project structure

```
app/src/main/java/com/example/gpswaypoint/
├── model/
│   ├── Waypoint.kt          – GPX waypoint data class
│   ├── TrackPoint.kt        – Recorded position sample
│   └── NavigationState.kt   – Sealed UI state class
├── util/
│   ├── GeoUtils.kt          – Haversine distance, bearing, formatting (pure)
│   ├── GpxParser.kt         – XML pull-parser for GPX files (pure)
│   └── TcxWriter.kt         – TCX file serialiser (pure)
├── ble/
│   └── HeartRateManager.kt  – BLE scan + GATT connection + HR parsing
├── service/
│   └── NavigationService.kt – Foreground service (GPS, compass, recording)
└── ui/
    ├── DirectionArrowView.kt – Custom canvas arrow view
    ├── NavigationViewModel.kt– AndroidViewModel bridging service ↔ UI
    └── MainActivity.kt       – Single activity: permissions, binding, rendering
```

---

## Permissions requested

| Permission | Reason |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS fix (mandatory) |
| `ACCESS_COARSE_LOCATION` | Fallback / pairing with fine location |
| `ACCESS_BACKGROUND_LOCATION` | Recording while screen off |
| `FOREGROUND_SERVICE` | Keep service alive |
| `FOREGROUND_SERVICE_LOCATION` | Required sub-type (API 34+) |
| `BLUETOOTH_SCAN` | Discover BLE heart-rate monitor (API 31+) |
| `BLUETOOTH_CONNECT` | Connect to discovered peripheral (API 31+) |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | BLE on API ≤ 30 |

No `WRITE_EXTERNAL_STORAGE` is required — TCX files are written to the app's
own external files directory.

---

## Building

### With Docker (recommended — no local SDK needed)

```bash
# Build the image
docker build -t gps-waypoint-builder .

# Run tests + assemble debug APK
docker run --rm -v "$(pwd)":/app gps-waypoint-builder
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Locally (Android Studio / command line)

Requirements: JDK 17, Android SDK with build-tools 34, Gradle 8.4.

```bash
./gradlew test assembleDebug
```

---

## Running unit tests only

```bash
./gradlew test
# or inside Docker:
docker run --rm -v "$(pwd)":/app gps-waypoint-builder \
    ./gradlew test --no-daemon
```

Test reports: `app/build/reports/tests/testDebugUnitTest/index.html`

---

## TCX output location

Recordings are saved to the app-specific external directory — no runtime
permission is needed to write there:

```
/sdcard/Android/data/com.example.gpswaypoint/files/recordings/activity_YYYYMMDD_HHmmss.tcx
```

Files survive app updates but are removed if the app is uninstalled.
You can retrieve them with Android File Transfer, `adb pull`, or any file
manager that has access to `Android/data/`.

---

## Architecture notes

- **Single activity** + **ViewModel** + **foreground service**: the service owns
  all sensor subscriptions and lives independently of the activity lifecycle.
- **Pure utility classes** (`GeoUtils`, `GpxParser`, `TcxWriter`) have no
  Android dependencies and are fully covered by JVM unit tests.
- **LiveData** drives all UI updates; the ViewModel translates raw service state
  into a sealed `NavigationState` hierarchy that the activity renders.
- **BLE** uses a simple scan-then-connect pattern.  The app does not remember
  the last device; it re-scans on each launch to stay simple and avoid storing
  a MAC address.

---

## Minimum SDK

API 26 (Android 8.0 Oreo).  The `FOREGROUND_SERVICE_LOCATION` permission and
`android:foregroundServiceType="location"` are required on API 29+ and declared
unconditionally so the manifest is forward-compatible.
