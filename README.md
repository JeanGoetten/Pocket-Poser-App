# Pocket Poser

**Website:** https://jeangoetten.github.io/Pocket-Poser-App/

Experimental Android application for extracting and viewing 3D skeletal motion from a video.

A single-person video can be imported or captured through Android's camera application. The user reviews the video, selects a time range and an optional focus window, and explicitly starts processing. Video preparation and tracking run locally; NVIDIA Body Pose inference requires the user's own NVIDIA API key. Results are displayed as a Nova-77 skeleton and can be saved or exported as JSONL. Empty or duplicate pose frames can be omitted from the preview and exported sequence without replacing the source capture.

## Project website

[Open the Pocket Poser website](https://jeangoetten.github.io/Pocket-Poser-App/)

The site contains installation and build instructions and links directly to an APK when a verified public GitHub Release is available.

## Install

1. Download the APK from the website or from [GitHub Releases](https://github.com/JeanGoetten/Pocket-Poser-App/releases) when one is available.
2. Install it on a compatible Android 8.0+ ARM device. Android may request permission to install from the selected source.
3. Review the privacy notice. For remote inference, configure your own NVIDIA API key and confirm the applicable terms for the media you process.

## Build from source

Requirements: JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and an Android SDK installation configured through `ANDROID_HOME` or a local, untracked `local.properties`.

Windows / PowerShell:

```powershell
git clone https://github.com/JeanGoetten/Pocket-Poser-App.git
cd Pocket-Poser-App
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Linux / macOS:

```sh
git clone https://github.com/JeanGoetten/Pocket-Poser-App.git
cd Pocket-Poser-App
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`. Debug builds are for local testing and are not public release packages.

## Scope and limitations

The tracker is experimental and may not detect every frame. NVIDIA processing is a third-party service subject to account terms, availability, usage quotas and possible charges. The export is pose data, not an animated video. The project includes an offline synthetic demonstration.

## License

Original project source is made available under [PolyForm Noncommercial 1.0.0](LICENSE.md). Personal and educational noncommercial uses are permitted subject to the license terms; commercial uses are not licensed. This is **source-available**, not OSI-approved open-source software.

Third-party code, SDKs, software interfaces and assets retain their respective licenses. See [third-party notices](THIRD_PARTY_NOTICES.md).

## Documentation

[Architecture](docs/ARCHITECTURE.md) · [Privacy overview](docs/PRIVACY.md) · [Security](docs/SECURITY.md)

## Privacy

The app processes video on the device and sends video without audio, tracking boxes and technical metadata to NVIDIA when authorized. Users must have the necessary rights to footage, including footage of other people. Remote data handling is subject to NVIDIA's applicable terms. No promise of remote deletion is made.

A complete, legally reviewed public privacy policy and verified signed APK are pending. Do not treat development builds as production releases.
