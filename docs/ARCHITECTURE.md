# Architecture — Pocket Poser 0.7.0 source preview

## Video and pose flow

1. User imports one video or records through the Android system camera.
2. Review selects a trim interval and an optional focus rectangle. Preparation removes audio and normalizes a short single-person video locally.
3. User selects exactly one processing engine:
   - NVIDIA Nova-77: local person tracking and TLS/gRPC upload of prepared video, bounding boxes and metadata, subject to a separate explicit remote-processing opt-in and user-supplied API key.
   - ML Kit: local on-device 33-point landmark inference.
   - MediaPipe Lite: local on-device 33-point landmark inference using a developer-provided, locally installed model asset.
4. Preview preserves source topology: 77 joint connections for NVIDIA or 33 native landmarks for the two offline engines. Invalid landmarks remain absent; low-visibility estimated landmarks are drawn as such. There is no 33-to-77 synthetic rig conversion.
5. Projects retain JSONL source and provenance; exports are motion data, not skinned-character animation.

## Local data and model setup

Android Keystore stores NVIDIA credentials. Local input, preparation files, checkpoints and pose projects use private app storage. The app permits source-frame filters without replacing original records. Offline output records 33 landmark positions and per-index confidence for informed interpretation.

The public source snapshot does NOT redistribute the upstream MediaPipe Lite model binary. See [Model setup](MODELS.md). It enforces a fixed SHA-256 at build time. This protects reproducibility, not redistribution permissions.

## SDK diagnostics

Input video processing and pose inference run on-device for the two offline modes; this does not establish that third-party SDKs never communicate technical metrics or configuration. See [Privacy](PRIVACY.md) and official Google notices. Remote NVIDIA transfer is a separate user-controlled operation.

## Limits

Only one person per video; incomplete pose detection, occlusion errors and camera/depth ambiguity remain possible. No calibrated skeleton retargeting or Unity/Godot animation export yet. Production signing and new public APK distribution remain gated by licensing and privacy review.
