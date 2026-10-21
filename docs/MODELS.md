# MediaPipe Lite model setup and provenance

This public source snapshot does **not** include the pretrained model binary used by the MediaPipe Pose Landmarker Lite mode. That file must be obtained separately from the official Google-hosted model location by developers who choose to build this source.

## Required upstream artifact

- Filename: pose_landmarker_lite.task
- Upstream URL: https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task
- Expected SHA-256: 59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a
- Expected length: 5,777,746 bytes
- Destination in a local checkout: app/src/main/assets/pose_landmarker_lite.task

Retrieve this file manually from the official upstream and check the digest. The Gradle build refuses to proceed if it is missing or has different content. The model file is ignored by Git and is not published here.

## License status

The MediaPipe SDK repository is Apache-2.0, but SDK code licensing alone does not establish the redistribution rights of an independently downloaded pretrained model. A Google MediaPipe maintainer explicitly confirmed Apache-2.0 for the **Pose Landmarker Full** task bundle in June 2026; that answer does not unequivocally cover this exact **Lite float16** artifact. See https://github.com/google-ai-edge/mediapipe/issues/6306 .

No model license or sublicensing rights are inferred from its download URL. This repository does not provide a public binary containing this model pending artifact-specific license confirmation. Developers remain responsible for their own usage terms and cannot treat a successful local build as permission to redistribute the model or APK.

## Build integration

The Android source uses MediaPipe Tasks Vision 0.10.35. The Android Gradle build checks for the exact model file and verifies SHA-256 before compiling. The model is loaded from the installed application assets, so no runtime download is needed for pose inference after a local build. Independent SDK diagnostics/metrics are addressed in [Privacy](PRIVACY.md).
