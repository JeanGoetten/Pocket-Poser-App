# Pocket Poser

**Website:** https://jeangoetten.github.io/Pocket-Poser-App/

Pocket Poser is an experimental Android application for processing one-person video into skeletal pose sequences. This independently curated public-source snapshot is **v0.7.0-beta.1 (source preview)**. It preserves an independent, sanitized Git history.

## Status and downloads

- **Latest public APK:** v0.6.0-beta.1, development-signed, NVIDIA-only pipeline. Download it from [GitHub Releases](https://github.com/JeanGoetten/Pocket-Poser-App/releases).
- **Latest source:** v0.7.0-beta.1, including three selectable engines (NVIDIA, ML Kit and MediaPipe Lite).
- **No 0.7.0 public APK yet.** The MediaPipe Lite model bundle is NOT committed here. Redistribution terms for the exact bundle, SDK telemetry disclosures, legal privacy review and release-signing readiness require further verification before publishing a new public APK.

These versions are not interchangeable. The latest public APK does **not** contain the newer offline modes.

## Engines

| Engine | Processing | Output | Requirements |
|---|---|---|---|
| NVIDIA Nova-77 | Remote | Native 77-joint skeleton and rotations | User's own NVIDIA API key and separate remote-processing consent |
| Google ML Kit | On-device | 33 estimated body landmarks (3D approximation) | Bundled SDK |
| MediaPipe Pose Landmarker Lite | On-device | 33 estimated body landmarks and visibility confidence | Model file installed locally in build, see docs/MODELS.md |

Offline processing does not transmit input video to NVIDIA. Some underlying SDKs may perform independent diagnostics or usage-metric communications; consult [Privacy](docs/PRIVACY.md). Landmark coverage is not the same as movement fidelity. Missing or occluded points can be inaccurate; low-visibility estimates are rendered with dashed lines. The 33-point skeleton is not converted into a fake 77-joint hierarchy and is **not** a Unity-ready animated rig.

## Interface

The [project website](https://jeangoetten.github.io/Pocket-Poser-App/) illustrates an actual Pocket Poser synthetic pose preview. That image is illustrative; it is not evidence of reliable real-video reconstruction or of the latest 0.7.0 interface.

## Install

The only public pre-release binary currently available is v0.6.0-beta.1. Install it from [GitHub Releases](https://github.com/JeanGoetten/Pocket-Poser-App/releases) on an Android 8.0+ ARM device. It uses development signing, is not production software, and may be superseded by newer unpublished test builds.

## Build this source snapshot

Requires JDK 17, Android SDK Platform 35 and Build Tools 35.0.0. Set ANDROID_HOME (or use a local, untracked local.properties). **Before building**, obtain the MediaPipe Lite task asset from its official URL and verify its SHA-256 exactly as described in [Model setup and provenance](docs/MODELS.md). Without it, the build deliberately stops with a clear error.

Windows / PowerShell:

```powershell
git clone https://github.com/JeanGoetten/Pocket-Poser-App.git
cd Pocket-Poser-App
# Follow docs/MODELS.md first to obtain/verify the local model file.
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Linux / macOS:

```sh
git clone https://github.com/JeanGoetten/Pocket-Poser-App.git
cd Pocket-Poser-App
# Follow docs/MODELS.md first to obtain/verify the local model file.
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Debug APK output is app/build/outputs/apk/debug/app-debug.apk. A locally built APK is signed with a development key and does not imply that the exact MediaPipe model's redistribution permissions have been cleared. Do not publish that resulting APK without reviewing applicable model and SDK terms.

## Known limitations

This remains a beta development application. Only one person is supported; partially occluded limbs may be inferred inaccurately. Local 3D landmarks do not directly provide calibrated bone rotations, retargeting, glTF/BVH/FBX motion, or a character animation for Unity. Real NVIDIA inference depends on service availability, account terms, quota, possible charges and consent. The export is pose data, not an animated video.

## Documentation

[Architecture](docs/ARCHITECTURE.md) · [Privacy](docs/PRIVACY.md) · [Security](docs/SECURITY.md) · [Model setup](docs/MODELS.md) · [0.7.0 source notes](docs/releases/v0.7.0-beta.1-source.md) · [Third-party notices](THIRD_PARTY_NOTICES.md).

## Licensing and publication

Original source is made available under [PolyForm Noncommercial 1.0.0](LICENSE.md). It permits the relevant noncommercial uses subject to license terms; this is source-available, **not** OSI-approved open source. Upstream SDKs, tools and model bundles have independent licenses and usage rules. The public repository excludes private project history, developer diagnostics, real-person recordings, credentials, device identifiers, and the MediaPipe model binary. New releases require separate technical and legal review.
