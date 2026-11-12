# Adrienne Zoo — Wildlife Companion (Android)

Adrienne Zoo is an offline-first native Android companion app for discovering and following real tracked wildlife. Users follow authentic animal migration journeys using real GPS and satellite telemetry from open wildlife research datasets (Movebank / Migration Watch).

## Architecture & Features

- **Native Android UI**: Built with Jetpack Compose, Material 3, and Kotlin Coroutines/Flow.
- **Offline Mapping Engine**: High-performance interactive projection maps powered by offline D3 and Natural Earth TopoJSON vector geometries rendered in an isolated local WebView.
- **Local Persistence & Data Safety**: Android Room database with explicit, non-destructive schema migrations. User unlock states and activated animal collections are strictly decoupled from catalog metadata updates and cannot be revoked by re-imports.
- **Wildlife Pack Support (`.wildpack` v1)**: Self-contained ZIP containers for modular offline distribution of research tracks, animal profiles, activation codes, and scientific attribution.
- **100% Offline Capability**: Zero runtime network dependencies, zero analytics, zero external tile servers. All starter animals and base geometries are bundled inside the APK.

## Project Structure

```
Adrienne_zoo/
├── android_companion_app/        # Primary Android Studio project
│   ├── app/                      # Main application module
│   │   ├── src/main/assets/      # Bundled map, starters catalog, vector land data
│   │   └── src/main/java/        # Compose UI, Room DB, ViewModels, Importer
│   ├── build_map_assets.cjs      # Bundles offline map HTML/CSS/JS/TopoJSON assets
│   └── extract_starters.cjs      # Reproducible starter catalog generator
├── data/                         # Source wildlife telemetry datasets
│   └── tracks.json               # Movebank research telemetry (CC0)
├── fixtures/                     # Compatibility test fixtures
│   └── sample-pack.wildpack      # Version 1 reference wildpack container
├── archive/                      # Historical prototypes
│   └── desktop-publisher/        # Retired desktop React publisher
├── PACK_FORMAT.md                # Wildpack v1 specification
└── UPSTREAM-LICENSE              # Dataset licensing & attribution notices
```

## Android Development Workflow

The Android companion application is completely self-contained in `android_companion_app/`.

### Requirements
- Android Studio Ladybug or newer
- JDK 21 (configured automatically via Gradle Daemon JVM Toolchain)
- Android SDK 34 (minSdk 28)

### Building and Testing
```bash
cd android_companion_app
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

To install on a connected device:
```bash
./gradlew installDebug
```

*Note: Node.js and desktop publisher builds are not required for regular Android development or app compilation.*

## Data Provenance & Attribution

Tracking records originate from Movebank (movebank.org) research studies and are curated under CC0 1.0 Universal public domain dedication. Upstream citations and licenses are preserved in `UPSTREAM-LICENSE` and within individual animal profile records.
