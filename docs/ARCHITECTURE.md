# Architecture

Pocket Poser is an Android application built with Kotlin and Jetpack Compose.

## Processing

1. Android's document picker or system camera returns a video URI.
2. The user previews the video, selects a time segment and may set a normalized focus rectangle.
3. After confirmation, the app imports the selected media into private storage and removes audio.
4. Local processing normalizes the selected region to H.264 SDR at 30 frames/s and performs experimental single-person tracking with OpenCV.
5. The app sends the prepared video, tracking boxes and metadata directly to NVIDIA Body Pose using TLS/gRPC and the user's API key.
6. Nova-77 joint data is rendered in the viewer and can be saved as a local project or exported as JSONL.

The Python gateway in the private development environment is not required by the Android production path and is not included in this distribution.

## Storage

Credentials are kept locally using Android Keystore. Work in progress may preserve prepared files in the app's private, non-backed-up area for explicit recovery. Saved projects include an immutable source JSONL and optional non-destructive edits and frame-filter settings.

Preview smoothing does not create NVIDIA observations. The Hide Empty Frames and Remove Duplicate Frames settings change playback and filtered JSONL export without rewriting the source capture. Filtered exports use consecutive `frame_id` values and retain `source_frame_id` for traceability.

## Dependencies and rights

The Nova-77 skeleton hierarchy and protocol definitions include NVIDIA-derived material under its upstream MIT notice. Other dependencies retain their upstream licenses. See [Third-party notices](../THIRD_PARTY_NOTICES.md).

## Limitations

Only one person per clip is supported. Local detection may be incomplete, particularly in poor lighting or with partial visibility. The service's terms, quotas and retention policies are separate from the application's implementation.
