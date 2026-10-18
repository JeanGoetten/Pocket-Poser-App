# Privacy overview (development)

This document describes the application's currently intended technical behavior. **It is not a finalized legal privacy policy** and should not be presented as one.

## Local processing

Videos are selected through Android's document picker or recorded by the device's camera application. Pocket Poser does not request broad storage access or direct camera/microphone permissions for these operations. The review screen allows a time-range selection and an optional rectangular focus region. Media import, audio removal, normalization and one-person tracking happen locally after explicit confirmation.

Prepared input and tracking data may be kept in private, non-backed-up app storage to support an explicitly requested retry. Finishing, discarding or revoking removes pending processing files. Saved projects and exported files have their own deletion controls; exports are managed by the storage destination chosen by the user.

## Remote processing

With the user's NVIDIA API key and required authorization, the app connects directly to NVIDIA's service using TLS/gRPC. It sends the selected prepared video without audio, tracking boxes and technical metadata. If a focus region is selected, it is applied before transmission. NVIDIA's own retention and deletion commitments must be verified against the actual product and account terms before real-person videos are distributed or processed. Local revocation cannot guarantee retroactive deletion by NVIDIA.

Do not upload recordings unless you have appropriate rights and permissions, including permissions relevant to other people appearing in the video. The applicable legal basis may differ from the user's own app consent.

## Credentials and telemetry

The API key is stored locally using Android Keystore and is not committed to the repository. No advertising or analytics SDK is intentionally integrated. Repository issues, builds, samples and tests must not contain real people's recordings, access tokens, personal paths or device identifiers.

## Before a production release

The maintainer still needs to establish the appropriate legal controller roles, contact channel, processing countries and transfer terms, service/provider retention guarantees, relevant lawful bases and procedures for rights requests. The in-app draft policy must then be reviewed and updated, with fresh consent where necessary. Until this is complete, experimental development-signed APK prereleases are for testing only; no production-ready APK is offered here.
