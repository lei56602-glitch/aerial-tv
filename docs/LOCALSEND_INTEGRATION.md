# LocalSend integration plan

## Product decision

Integrate the complete LocalSend experience into Aerial TV as an in-process feature reachable from **Settings → Personalization → LocalSend**. It must not launch or require a separately installed LocalSend APK. The deliverable is one installable Aerial APK.

The integration must preserve Aerial's existing HOME behavior, settings, user data, package ID (`app.orchard.tv`), standard/guard flavors, and no-ad policy. Do not change unrelated wallpaper, screensaver, launcher, phone setup, or guard behavior.

## Repository baseline

- Host app: `lei56602-glitch/aerial-tv`, default branch `main`.
- Feature branch: `feature/localsend-integration`.
- Embedded feature source: `lei56602-glitch/localsend`, default branch `main`.
- Aerial is native Kotlin + Jetpack Compose, minSdk 21, compileSdk 36, targetSdk 34, and has standard/guard flavors.
- LocalSend's Android application is Flutter. Its app package is `org.localsend.localsend_app`; the app uses Dart packages and Flutter plugins. The Rust `packages/core` crate is not declared as a dependency by `app/pubspec.yaml`, so it is not the app's current transfer implementation.
- LocalSend's current compatibility statement says Android 7.0 minimum; Aerial currently supports Android 5.0. Compatibility must be resolved explicitly rather than silently raising Aerial's minimum SDK.

## Integration direction

The first implementation candidate is Flutter add-to-app: embed LocalSend's existing Flutter application experience and business logic inside the Aerial APK, then expose it through a native Aerial entry under Personalization. This is the strongest route to preserving the full upstream feature set (discovery, send/receive, QR/link workflows, transfer queue/progress, settings, history, translations, protocol/security behavior) without reimplementing the protocol in Kotlin.

Before wiring it into the host build, prove a minimal add-to-app build against the exact LocalSend fork and Aerial toolchains. The existing LocalSend Android application module cannot simply be copied into Aerial: it is a separate Flutter application, with its own applicationId, target SDK, Flutter Gradle plugin, manifest, and plugin registrations.

If Flutter add-to-app cannot meet Aerial's Android 5.0 support or APK/runtime constraints, evaluate a pinned older compatible LocalSend release or a carefully scoped protocol/core port. Do not claim full feature parity for a partial Kotlin rewrite. Do not use the Rust crate as a shortcut unless an Android FFI layer and feature parity are actually demonstrated.

## UI and TV requirements

- Aerial owns the entry point and navigation; LocalSend must appear under Personalization.
- Keep the LocalSend workflows intact, but adapt all reachable controls for a TV remote: D-pad focus, OK, Back, focus visibility, overscan-safe layout, and 16:9 landscape.
- No touch-only dead ends. File/folder selection must use Android TV-compatible document provider flows or a TV-friendly picker.
- Keep discovery and transfer services alive only for the required lifecycle; handle activity pause/resume, process recreation, and network changes.
- Preserve LocalSend protocol compatibility with existing LocalSend clients. Do not introduce a relay server or cloud dependency.

## Implementation phases

1. **Compatibility spike**: pin both repository revisions; determine Flutter/Dart/Gradle/AGP/JDK/NDK requirements; test a Flutter add-to-app host activity and plugin registration; verify minSdk and ABI behavior. No changes to Aerial's production build until this succeeds.
2. **Embedded runtime**: package the complete LocalSend Flutter feature into the Aerial APK, preserve its Dart services, assets, localization, permissions, and native plugin registrations. Keep the LocalSend applicationId out of the host manifest.
3. **Aerial entry and TV adaptation**: add the Personalization row and embedded route; adapt focus/back/file selection and landscape layouts without removing LocalSend workflows.
4. **Regression and protocol tests**: build standard and guard release variants; test LocalSend-to-Aerial and Aerial-to-LocalSend discovery, QR/manual peer connection, encrypted transfer, multiple files, large files, accept/reject, failures, history, and settings. Test on Android TV hardware and supported Android API levels.
5. **Release**: update versionCode according to Aerial's handoff rules, build signed release APKs using the owner's existing signing setup, and publish only after real-device verification.

## Non-negotiable acceptance criteria

- One Aerial APK; no separate LocalSend installation.
- Entry is under Personalization.
- Existing LocalSend clients can discover and exchange files with Aerial.
- Full LocalSend workflows are retained or any unsupported TV-only workflow is explicitly documented; no silent feature deletion.
- D-pad/Back operation works throughout.
- Existing Aerial launcher, phone setup, screensaver, guard, and user data regressions are absent.
- No ads, ad SDKs, or third-party relay service.
- Never report a feature as complete without a successful build and real-device verification.
