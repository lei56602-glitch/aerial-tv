# Aerial LocalSend native bridge

This crate is the Android JNI boundary between Aerial's Kotlin app and the
pinned LocalSend Rust protocol core in `third_party/localsend/packages/core`.

- It uses the upstream LocalSend core with its `full` feature set.
- The JNI surface uses JSON strings and will be versioned independently from
  LocalSend's Flutter Rust Bridge bindings.
- The first function exposes LocalSend's native TLS identity generation.
  Discovery, HTTP client/server, transfer streaming, cancellation, and event
  callbacks are the next bridge surfaces; they are not implemented yet.

Build with Rust 1.97.1, Android NDK, and cargo-ndk:

```sh
cargo ndk -t arm64-v8a --platform 21 build --release --manifest-path native/localsend/Cargo.toml
```

Do not call this integration complete until all discovery and transfer
operations are bridged and interoperable with existing LocalSend clients.
