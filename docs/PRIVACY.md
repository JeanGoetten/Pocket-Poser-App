# Privacy — early-access disclosure

Pocket Poser handles user-selected video for single-person motion analysis. The source is reviewed and prepared locally (time selection, focus, audio removal and normalization). **Google ML Kit is used locally only to help detect the person's bounding box**; ML Kit may separately contact Google for SDK configuration or diagnostics under its own terms. The application does not create an ML Kit 3D pose sequence.

**NVIDIA processing is optional until explicitly authorized, but is required for real-video 3D pose generation.** A user registers their own NVIDIA account and enters their own API key, kept with Android Keystore; the app sends the selected audio-free video, tracking boxes and technical metadata directly to NVIDIA using TLS/gRPC. Provider availability, billing, logging, retention, location of processing and deletion are governed by NVIDIA's current terms, not controlled by Pocket Poser.

Projects, temporary processing files and consent records are stored locally in app-private storage. Partial stages may be retained for explicit recovery; saved projects persist until deleted. Sharing or exporting creates a file in the destination chosen by the user. Revoke app consent to block new processing; this cannot guarantee cancellation or deletion of data already received by NVIDIA. Android app-data deletion clears app-private data; app backup and transfer are disabled.

No ads, analytics platform or training of models by Pocket Poser are implemented. Do not use a video of other people without applicable rights and consent. The service and this software are experimental. For security or private data concerns, use GitHub's private vulnerability reporting if available; **never post personal footage, API keys or identifying data in public issues**.

This document describes the application as distributed; it is not a guarantee about third-party telemetry, compliance in every region or any NVIDIA account-specific commercial terms. Legal privacy/contact review remains ongoing.
