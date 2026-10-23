# Architecture

**Android client:** Kotlin, Jetpack Compose, Android MediaCodec/MediaExtractor, OpenCV and gRPC/TLS.

**Processing:** Android system picker or camera app -> video trim/focus -> normalization (audio removed) -> single-person detection/tracking boxes (ML Kit and OpenCV) -> authorized direct NVIDIA Body Pose / Nova-77 remote inference -> JSONL result -> local 3D preview, corrections and export.

Google ML Kit is used for local person-box detection only. It does not supply pose sequences. There is no MediaPipe runtime, no local 3D pose generator, no Pocket Poser proxy service and no Unity retargeting.

Consent and API credentials are held on the device. A saved key is not a network request; upload starts only after review and the user's action. Checkpoints can survive an interrupted NVIDIA job so a full video preparation may not need to be repeated. SDK diagnostic/network activity is separately documented in [privacy](PRIVACY.md).
