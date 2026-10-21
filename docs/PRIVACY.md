# Privacy overview (development only)

**This is not a finalized legal privacy policy.** The 0.7.0 source preview is not a public production release. Legal/controller and consent language requires further review before a new distributable APK is published.

## Local capture and offline pose processing

The Android document picker and system camera provide the selected video. Trim, focus, audio removal, normalization, ML Kit and MediaPipe landmark inference run locally. The app does not send the selected video or extracted local landmarks to NVIDIA in offline mode. Source and projects are stored privately on the device until saved/shared/removed by the user. Temporary processing files may be retained for explicit recovery or until cleanup.

## Third-party telemetry and diagnostics

Local video inference **does not imply zero network traffic**. The Google ML Kit Android SDK may use Firebase Remote Config / Firebase Installations for configuration and diagnostics; SDK data disclosure requirements apply. The MediaPipe Tasks privacy notice discusses performance/usage metrics and informed consent. The exact Android SDK version's network behavior needs device-level inspection and legal evaluation. Review the official material:
- https://developers.google.com/ml-kit/android-data-disclosure
- https://developers.google.com/edge/mediapipe/solutions/tasks#mediapipe_tasks_privacy_notice

This snapshot should not be used to promise that no technical information ever reaches upstream providers. Diagnostic disclosures and any required user opt-in must be finalized before a new public binary is distributed.

## Optional remote NVIDIA processing

The NVIDIA engine requires both the user's key (stored with Android Keystore) and explicit authorization for remote transfer. The app sends the selected silent video and technical pose/tracking data over TLS/gRPC to NVIDIA. The applicable account terms, retention, deletion promises and processing locations are independent of the app. Revoking local permission cannot guarantee deletion from NVIDIA systems.

Do not process identifiable people without sufficient legal rights and permissions; separate rights may apply to bystanders or footage subjects.

## Outstanding production items

Establish a legal contact/controller identity, lawful basis for processing and transfers, SDK diagnostic telemetry obligations, rights requests, retention and deletion statements, and final NVIDIA/provider terms. Produce a separately signed, audited release only after completing those reviews. Never commit real footage, tokens, identity metadata, personal file paths or private Git history to this public repository.
