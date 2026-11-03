# Spektrafilm for Android

An Android app that runs the full **spektrafilm** spectral film simulation on
the phone: scene light → film exposure → development (DIR couplers, grain) →
enlarger printing → scan. Built for personal use (tested on an OPPO Find X9).

- **Engine**: [spektrafilm-rs](https://github.com/turbasvin/spektrafilm-rs), a Rust port of
  [spektrafilm](https://github.com/andreavolpato/spektrafilm) by Andrea Volpato.
  It is vendored under `third_party/spektrafilm-rs` with small Android patches.
- **GPU**: wgpu on Vulkan. The engine falls back to the CPU automatically if the
  GPU can't run the shaders or panics.

## Features

- **Input**: gallery photos (JPEG/HEIF, decoded to sRGB) and **RAW/DNG**. RAW
  files are decoded with rawler, stay linear, and are rotated according to EXIF.
- **Stocks**: 22 film stocks (colour negative, slide, B&W) and 9 papers/print films.
  - The film roll shows thumbnails rendered from your own photo.
  - The paper choice is automatic, a specific paper, or a direct scan of the film.
- **Parameters**: about 35, all with upstream defaults. Only values you change are
  sent to the engine. Covered areas:
  - exposure and metering
  - print exposure and preflash
  - Y/M enlarger filters
  - film and paper contrast, developer exhaustion
  - DIR couplers
  - grain and film format
  - halation, scatter and highlight boost
  - diffusion filters
  - print glare
  - lens and scanner sharpness
- **Frames**: paper border, 35mm rebate with perforations, 120 rebate, instant.
  The rebate colours follow the output path (print, scanned negative, scanned
  slide). There are no manufacturer marks.
- **Viewing**: full-screen preview with pinch zoom. Press and hold to compare
  with the original.
- **Export**: full-resolution JPEG to `Pictures/Spektrafilm`.

## Layout

```
app/                         Android app (Kotlin, framework views, no extra libraries)
rust/spektrafilm-jni/        JNI bridge: engine.rs (pure Rust, unit-tested) + lib.rs (JNI, jnigraphics)
third_party/spektrafilm-rs/  vendored upstream port (commit 9dd59b0) with Android patches applied
patches/                     the Android patches as a diff against upstream
scripts/build.sh             one-shot APK build
```

## Build

Requirements:

- JDK 17
- Android SDK 36 with build-tools 35
- NDK 28.2.13676358
- Rust with the `aarch64-linux-android` target
- `cargo-ndk`

```bash
scripts/build.sh            # -> app/build/outputs/apk/release/app-release.apk
scripts/build.sh install    # also installs on a USB-connected phone via adb
```

Gradle's `preBuild` step does two things automatically:

- runs `cargo ndk` (arm64-v8a) to build the JNI library;
- copies the spectral data (profiles, LUTs, filters) into the APK assets.

The release build is signed with the debug key, which is fine for personal use.

## Tests

The engine (`rust/spektrafilm-jni/src/engine.rs`) has unit tests. They run the
full CPU pipeline and check:

- resampling
- RAW orientation
- sRGB round-trip
- stride handling
- error handling

There is also a desktop renderer that writes the original and the simulation side by side:

```bash
cargo test --release
cargo run --release --example render -- third_party/spektrafilm-rs/data in.dng out.png kodak_portra_400 "" "" 1600
```

On Windows these were run inside WSL Ubuntu, because the Windows-gnu host build
of wgpu crashes at load time. That is a host-toolchain issue and does not affect Android.

## Upstream patches

See `patches/spektrafilm-rs-android.patch`:

1. `spectral_service.rs`, `cpu_backend.rs` and two `Cargo.toml` files: Android
   uses the pure-Rust matrix multiply instead of linking OpenBLAS.
2. `wgpu_backend.rs`: adapters that can't run `@workgroup_size(1024)` are
   refused, so the CPU path takes over instead of panicking at pipeline creation.

## Licenses

- **Code**: GPLv3 (see `LICENSE`), the same as spektrafilm and spektrafilm-rs.
- **Film/paper profiles and spectral data**: CC BY-SA 4.0 with the spektrafilm
  preamble (see `SPEKTRAFILM_LICENSE.txt`).

Film modeling is powered by spektrafilm.
