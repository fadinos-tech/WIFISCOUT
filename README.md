# WiFi Scout

Android app that draws a live WiFi heatmap of your home while you walk around,
detects roaming between the router and mesh extenders, and marks where an
additional extender is needed.

## How it works

- **Step tracking** — accelerometer + rotation vector estimate your position indoors (no GPS).
- **Heat field** — every RSSI sample is splatted into a world-space grid using
  inverse-distance-weighted interpolation, producing one smooth WiFiMan-style
  heat layer instead of overlapping blobs.
- **Roaming detection** — a BSSID change while scanning is marked on the map
  (flash ring / lightning / banner / color-split styles).
- **Extender suggestion** — when a scan stops, the largest cluster of weak
  readings (≤ -72 dBm) is located and an "Extender here" marker is drawn at its center.
- **Export** — save the map to the gallery, share it as PNG, or export the raw data as CSV.

## Versioning

The app version is defined in **one place only**: `gradle.properties`

```
APP_VERSION_NAME=1.0.1
APP_VERSION_CODE=2
```

`app/build.gradle` and the in-app version label (`BuildConfig.VERSION_NAME`)
both pull from there — never edit the version anywhere else.

## Build

Open in Android Studio, or:

```
gradlew :app:assembleDebug
```

- AGP 8.2, Gradle 8.14, minSdk 26, targetSdk 34.

---
Click Solutions Pro
