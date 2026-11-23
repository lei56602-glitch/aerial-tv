# LocalSend integration plan

## Product decision

Integrate the complete LocalSend transfer experience into Aerial TV as an in-process feature reachable from **Settings → Personalization → LocalSend**. It must not launch or require a separately installed LocalSend APK. The deliverable is one installable Aerial APK.

Preserve Aerial's HOME behavior, settings, user data, package ID (`app.orchard.tv`), standard/guard flavors, Android 5.0 minimum SDK, and no-ad policy. Do not change unrelated wallpaper, screensaver, launcher, phone setup, or guard behavior.

## Repository baseline

- Host app: `lei56602-glitch/aerial-tv`.
- Working branch: `feature/localsend-integration`.
- LocalSend source is pinned as a Git submodule at `third_party/localsend`, URL `https://github.com/lei56602-glitch/localsend.git`, commit `281b491e9c0d0e692fc7ff1caaea28c897a64fd0` (LocalSend main as inspected on 2026-11-23). Clone with `git submodule update --init --recursive`.
- Aerial is native Kotlin + Jetpack Compose, minSdk 21 (Android 5.0), compileSdk 36, targetSdk 34, with standard/guard flavors.
- LocalSend's standalone Android app is Flutter. Its app package is `org.localsend.localsend_app`; it uses Dart packages and Flutter plugins. The Rust core is in `third_party/localsend/packages/core`, and the existing Flutter-to-Rust bridge is in `third_party/localsend/packages/localsend_isolates`.
- The existing Rust bridge crate already enables the LocalSend core's `full` feature set and contains working wrappers for identity/crypto, discovery, HTTP client operations, upload progress, cancellation, and Android file descriptors.
- LocalSend's current Flutter generation cannot be embedded directly without breaking Aerial's compatibility promise: Flutter 3.35+ raised its Android minimum to API 24 (Android 7.0), while Aerial must remain installable on API 21 (Android 5.0). Do not use `tools:overrideLibrary` to conceal this runtime incompatibility, and do not raise Aerial's minSdk.
- The Flutter module experiment is therefore not the production integration route. A draft experiment exists separately in LocalSend PR #1; it is not merged and must not be treated as the Aerial implementation.

## Integration architecture

Use **Aerial-native Kotlin/Compose UI + LocalSend Rust protocol core through JNI/UniFFI**.

1. Build the pinned Rust LocalSend core for Android ABIs with Android NDK and an API-21-compatible target. The existing `rust_builder/android/build.gradle` declares minSdk 19, which is useful evidence for the current Rust bridge packaging, but the new host bridge still needs an actual API-21 build and device test.
2. Add a small Android FFI crate/bridge around `third_party/localsend/packages/core`. Reuse LocalSend's Rust implementation for discovery, multicast, HTTP/HTTPS, TLS identity and certificate pinning, file upload/download, checksums, and cancellation. Do not reimplement cryptography or protocol wire formats in Kotlin.
3. Port the app-level orchestration currently in LocalSend's Dart code to Kotlin: device list/state, transfer queues, accept/reject, progress, history, persisted settings, receive server lifecycle, QR/manual connection flows, and error recovery. Preserve the behavior and protocol compatibility of the pinned LocalSend source.
4. Build the complete TV interface in Aerial's Compose design system and expose it under Personalization. Adapt all operations for D-pad/OK/Back, 16:9 landscape, visible focus, and Android TV document/file pickers.
5. Keep all required receive-server/background behavior within the Aerial process and Android lifecycle. Merge only necessary permissions/services; do not copy LocalSend's standalone application ID, launcher activity, icon, quick-settings tile, or Flutter app shell.

The Rust core is a protocol engine, not the whole LocalSend product. A JNI wrapper alone is not full integration: Kotlin must also implement the app-level orchestration and all user-facing workflows. Do not mark a feature complete merely because the native library builds.

## Required feature parity

- LAN discovery, multicast announcements, subnet scanning, device deduplication and multiple network interfaces.
- Device identity, certificates, fingerprints, encryption, PIN/security settings and peer verification.
- Send and receive files, multiple files, large files, file metadata, checksums and previews where supported.
- Prepare/accept/reject flows, upload/download, progress, cancellation, failures and retry handling.
- QR/link sharing and manual peer connection where supported by the protocol.
- Transfer history and management.
- Device name, network interface filters, receive location, protocol/security preferences and all relevant LocalSend settings.
- LocalSend interoperability with existing Android, iOS, Windows, macOS and Linux clients; no cloud relay or third-party server.

Any feature that depends on a platform-specific capability unavailable on Android TV must be explicitly identified and replaced with a TV-appropriate equivalent, not silently dropped.

## Implementation phases

1. **Source and ABI baseline**: verify the submodule commit, inspect all Rust core APIs and existing Flutter bridge wrappers, establish NDK/Cargo toolchain, and compile the Rust core for each Aerial ABI with API 21. Do not alter Aerial's minSdk.
2. **Native bridge**: add the JNI/UniFFI wrapper with structured DTOs and event streams for discovery, transfer progress, cancellation, and errors. Add Rust unit/integration tests and JNI smoke tests.
3. **Kotlin service layer**: port LocalSend's Dart orchestration and persistence to Kotlin without changing the LocalSend protocol. Implement receive server lifecycle, transfer queue, device store, history, security settings, and storage access.
4. **Personalization UI**: add the LocalSend entry in Aerial's `WallpaperSheet.kt` (the Personalization panel) and launch the native transfer center. Preserve Aerial's existing visual system and remote-control focus behavior.
5. **Regression and protocol tests**: build standard and guard release variants; test discovery and bidirectional transfer against real LocalSend clients, encrypted sessions, multiple/large files, accept/reject, cancellations, failures, history, QR/manual connection, network changes, sleep/resume, and Android API 21/24/29/34+.
6. **Release**: follow `docs/HANDOFF.md` versionCode and signing rules, build signed APKs using the owner's existing signing setup, and publish only after real-device TV verification.

## Non-negotiable acceptance criteria

- One Aerial APK; no separate LocalSend installation.
- Entry is under Personalization.
- Existing LocalSend clients can discover and exchange files with Aerial.
- Full user-facing workflows and protocol behavior are retained; no silent feature deletion.
- D-pad/Back operation works throughout.
- Aerial remains minSdk 21; no Flutter engine/API-24 dependency in the base app.
- Existing launcher, phone setup, screensaver, guard, and user data regressions are absent.
- No ads, ad SDKs, cloud relay, or third-party service.
- Never report a feature as complete without a successful build and real-device verification.
