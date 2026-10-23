# Pocket Poser

**Website:** https://jeangoetten.github.io/Pocket-Poser-App/

Pocket Poser is an experimental Android app that estimates a single person's 3D skeletal movement from video with **NVIDIA Body Pose (Nova-77)**.

## Latest version

**0.8.0 — public early-access release.** [Download the Android APK](https://github.com/JeanGoetten/Pocket-Poser-App/releases/tag/v0.8.0) or use the website above. Supports Android 8.0+ on ARM. No Google Play registration or payment is required to download the APK.

The original media is selected from device storage or captured with the system camera application. Review the time range and focus, then explicitly start preparation. The app locally normalizes video and estimates person bounding boxes using **Google ML Kit** (auxiliary detection only); after separate consent and a user-provided NVIDIA API key, it sends the audio-free video and technical tracking data to the NVIDIA service for Nova-77 inference. Preview skeletal poses, manage saved projects, and export JSONL.

**There are no local/offline 3D pose inference engines in 0.8.0.** MediaPipe has been removed. Google ML Kit does not generate the 3D motion data. The synthetic UI demo remains usable offline; previous experimental local 33-point project files are not supported.

## Build

Requirements: JDK 17, Android SDK Platform 35, Build Tools 35.0.0. Configure ANDROID_HOME or an untracked local.properties.

Windows:

    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

Linux/macOS:

    ./gradlew :app:testDebugUnitTest :app:assembleDebug

The debug APK is generated at app/build/outputs/apk/debug/app-debug.apk. Local debug signing keys differ from the distributed release's signing key and are not interchangeable for upgrades.

## Limitations and privacy

Single-person tracking, experimental detector confidence and 3D reconstruction; a high tracking-coverage percentage is not proof of accuracy. Input up to 250 MB, final NVIDIA upload up to 50 MB, with format/duration limits. Only data used for inference is sent to NVIDIA after opt-in; real inference requires NVIDIA account access and may incur independent costs or quotas. The ML Kit SDK can communicate technical diagnostics or configuration; see [privacy](docs/PRIVACY.md). Automatic Unity animation retargeting is not offered.

This independent project is provided for technical evaluation, with known limitations and no warranty. Do not process recordings of others without appropriate rights and consent. Report bugs via GitHub Issues, **without** uploading private video or credentials.

Original project code is licensed under [PolyForm Noncommercial 1.0.0](LICENSE.md) (source-available, not OSI-approved open source). Third-party SDKs and models remain subject to their own terms. See [third-party notices](THIRD_PARTY_NOTICES.md).

[Architecture](docs/ARCHITECTURE.md) · [Security](docs/SECURITY.md) · [Release notes](docs/releases/v0.8.0.md).
