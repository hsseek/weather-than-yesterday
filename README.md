# Weather Than Yesterday (어제보다)

An Android app that shows the current temperature compared to the same hour yesterday — so you always know whether to dress warmer or lighter than you did the day before.

## Features

- **Temperature comparison** — current hourly temp vs. the same hour yesterday, with ▴/▾/= indicators
- **3-day forecast** — high/low temperatures color-coded by warmth (red → warm, blue → cool)
- **Precipitation status** — rain, snow, or mixed, including when it starts or ends
- **Auto-location** — GPS-based detection, or choose from preset Korean regions (Seoul, Gangwon, Busan, Daegu, and more)
- **Manual location search** — search and pin any location
- **Home screen widgets** — Gray, Day, and Night temperature widgets via Jetpack Glance
- **Dark mode** — follows system preference or set manually
- **Simple / Daybreak view** — hide descriptive text or reorder temps chronologically
- **English / Korean** — in-app language switching

## Tech Stack

| Layer | Library |
|---|---|
| UI | Jetpack Compose, Compose BOM 2026.02.01 |
| Architecture | MVVM, ViewModel, StateFlow |
| Networking | Retrofit 3, OkHttp 5, Gson |
| Location | Google Play Services Location 21 |
| Preferences | DataStore + Protocol Buffers |
| Widgets | Jetpack Glance 1.1.1 |
| Background | WorkManager 2.11, foreground Service |

Data is sourced from the **Korea Meteorological Administration (KMA)** API.

## Requirements

- Android 12 (API 31) or higher
- Location permission for auto-detection (optional — preset regions work without it)

## Build

```bash
./gradlew assembleRelease
```

- compileSdk 36, targetSdk 36, minSdk 31
- Kotlin 2.3, JVM target 17
- Release builds use ProGuard minification and resource shrinking

## License

All rights reserved. © hsseek
