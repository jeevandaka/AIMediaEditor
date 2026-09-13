# AI Media Editor — Phase 1 + EDL core

Status: **early scaffold**, not a full app. Two things are genuinely done; everything else in both documents you shared is deliberately not attempted yet. See "Why it stops here" at the bottom.

## What's actually here

**1. Phase 1 foundation (per the original spec's own phasing):**
Android Studio project, dark Compose theme, a runtime permission flow that's correct across Android 8 through 17 (legacy storage permission, Android 13 granular media permissions, Android 14+ partial-access), and a home screen that queries MediaStore directly once permission is granted and shows every photo/video on the device in a grid. Photos render as real thumbnails via Coil; videos show a placeholder + play glyph (no decoded frame yet — see Known limitations).

**2. The EDL / AI-command core (in response to the architecture notes doc):**
`editor/model/` — `ProjectState`, `VideoClip`, `AudioTrack`, `TextOverlay` (the non-destructive Edit Decision List from architecture-notes section 1), the closed `EditCommand` taxonomy from section 7, and `ProjectSanitizer` — the validation gate that clamps every command against real project bounds. This package is plain Kotlin with no Android dependencies on purpose, so it isn't blocked by the same "no SDK in this sandbox" problem as everything else. Its trickiest arithmetic (clamping ranges, finding a valid split point) was hand-traced against edge cases — empty timelines, clips shorter than the minimum duration, wildly out-of-range AI output — and re-verified with a throwaway Python port of the same logic before being written into Kotlin, specifically so it wasn't just "looks right." That's a meaningfully higher bar than the rest of this scaffold, which is written-carefully-but-uncompiled.

## Explicitly not here yet

Everything else. That includes all of Phase 2 (manual timeline editor), Phase 3 (Media3 rendering/export), Phase 5 (media indexing/search), Phase 6 (polish) from the original spec — and, from the architecture notes doc, anything touching real Media3 playback, codecs, proxies, thermal/memory behavior, or on-device ML. Section-by-section:

| Architecture-notes constraint | Applies in | Status |
|---|---|---|
| Scoped storage, granular permissions, partial-access handling (§4.1) | Phase 1 | **Done** — this is what the permission flow above implements |
| EDL immutability, AI-as-compiler, validation gate (§1) | Phase 4 core | **Done** — `editor/model/` |
| AI command schema (§7) | Phase 4 | **Done** — `EditCommand.kt` |
| Codec starvation limits, single-surface/chained playback (§2.1) | Phase 2/3 | Not started — needs a real device to even observe the failure mode |
| VFR/PTS normalization, HDR/SDR tone-mapping (§2.2–2.3) | Phase 3 export | Not started |
| Export isolation via WorkManager + foreground service (§2.4) | Phase 3 | Not started |
| Proxy editing (540p/720p for scrub, full-res only at export) (§3.2) | Phase 2/3 | Not started |
| Sequential AI-model lifecycle (unload before playback) (§3.3) | Phase 4 | Not started (no on-device model loaded yet) |
| Bitmap pooling / hardware bitmaps for thumbnails (§3.4) | Phase 2 | Partial — Coil handles this for photos; video frames deferred |
| Staged indexing, no cold-start mass scan (§5.1) | Phase 5 | Not started — Phase 1's `MediaRepository` does a flat scan, fine for a few hundred items, wrong for a real library |
| Embedding storage / cosine similarity (§5.2) | Phase 5 | Not started |
| 60fps Compose timeline scrubbing, `derivedStateOf` (§6.1) | Phase 2 | Not started — no timeline UI exists yet |
| ExoPlayer surface lifecycle (§6.2) | Phase 2/3 | Not started |

Nothing in that "not started" list has been faked, stubbed, or half-implemented — it's just absent, per spec section 33's own rule against pretending unfinished features work.

## Project structure

```
AIMediaEditor/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/...
        └── java/com/aimediaeditor/app/
            ├── MainActivity.kt
            ├── ui/theme/Theme.kt
            ├── ui/home/{HomeScreen, HomeViewModel}.kt
            ├── ui/permissions/MediaPermissionState.kt
            ├── data/media/{MediaItem, MediaRepository}.kt
            └── editor/model/{ProjectState, EditCommand, ProjectSanitizer}.kt
```

Single Gradle module for now. Split into the `core-*`/`feature-*` layout from spec section 16 once there's enough surface area (Phase 2+) that the module boundary actually earns its build-time cost — not worth it at this size yet.

## Dependencies (current as of writing — let Android Studio's suggestions win if they differ)

| Library | Version | Why |
|---|---|---|
| Android Gradle Plugin | 9.3.0 | Latest stable as of July 2026 |
| Kotlin | 2.3.20 | Stable since March 2026 — deliberately not the literal-latest 2.4.20 (released days ago), to avoid pairing a brand-new compiler with everything else here |
| Compose BOM | 2026.08.00 | Latest stable; Compose 1.12 *requires* compileSdk 37, which is why that's set below |
| AndroidX Lifecycle | 2.11.0 | For `AndroidViewModel` + the `viewModel()` Compose helper |
| Coil | 3.3.0 (`io.coil-kt.coil3`) | Local `content://` URIs need no network module — just `coil-compose` |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | compileSdk 37 because Compose 1.12 requires it; targetSdk pinned one level back so Android 17's forced behavior changes (mandatory adaptive layouts, background-audio-via-Media3-only, etc.) don't land on a bare scaffold before Phase 3 is ready to handle them on purpose. Google's API-37 targeting deadline for Play Store is August 2027, so there's no compliance pressure to move targetSdk yet |

## Build instructions

1. Open the `AIMediaEditor/` folder in a current Android Studio (Panda or newer).
2. Let it sync Gradle — there's no committed wrapper here, so Studio will offer to generate one; accept it. It may also suggest a slightly different AGP/Kotlin version to match what it has bundled; accept that too, the numbers above are "current as of writing," not gospel.
3. Run on a device or emulator on Android 8.0 (API 26) or newer, with some photos/videos on it (or push sample files with `adb push file.jpg /sdcard/Pictures/`).

## APK

Build ▸ Build Bundle(s)/APK(s) ▸ Build APK(s) in Android Studio, output lands in `app/build/outputs/apk/debug/`. I can't produce that binary myself — this sandbox has no Android SDK, no emulator, and no network path to Google's Maven repo, so nothing here has actually been compiled. Android Studio (or Claude Code running on your machine) can build and run it for real; this chat can't.

## Setup instructions for any AI API — not needed yet

Phase 1/4-core has no live AI call — `EditCommand` is the shape an LLM's output will be parsed into, but nothing calls a model yet. When that's wired up, you'll need to pick:
- An LLM to turn a prompt into a JSON `EditCommand` list — e.g. the Claude API, called with a securely-stored key (never bundled into the APK).
- On-device ML (architecture-notes "Level 1") — Google's ML Kit covers face/object detection and scene labeling with no network call, which fits spec section 15's privacy principle better than a cloud model for routine indexing.

## Known limitations

- **Nothing here has been compiled or run.** Written carefully against current, verified-current-as-of-today library versions, but treat it as a strong first draft to fix up in Android Studio — except `editor/model/`, whose core arithmetic was actually tested (see above), just not as Kotlin.
- Video thumbnails show a placeholder + glyph, not a decoded frame.
- No pagination — `MediaRepository` loads everything in one query; fine for a few hundred items, wrong for a real multi-thousand-item library (architecture-notes §5.1 territory).
- Single module, no DI framework, no Room yet, no navigation graph (only one screen exists).
- `ProjectSanitizer` is pure and portable but has no automated test suite wired up — Phase 2 should add a `test/` source set and turn the Python edge cases in this session into real Kotlin unit tests.

## Testing instructions

Manual only (no SDK here to run instrumented tests):
1. Fresh install → permission screen, not a crash.
2. Deny permission → stays on the permission screen.
3. Grant on a device with zero media → "No photos or videos found" empty state.
4. Grant on a device with photos and videos → grid populates; videos show the play glyph.
5. Android 14+: choose "Select photos" (partial access) → app still loads whatever was selected.

## Why it stops here

Both documents describe a genuinely sound, well-researched system — the phased rollout, the non-destructive EDL, the codec/thermal/proxy constraints are all real problems real editors hit, not generic advice. But "never write toy or naive implementations" and "build all of Media3 rendering, thermal-aware playback, on-device ML sequencing, and vector search in one uncompiled chat response" are in direct tension: none of that can be verified without a real device, and code claiming to solve device-specific performance/thermal/memory problems without ever running on a device is exactly the toy implementation the instruction is warning against. The two pieces built here — Phase 1 and the EDL core — were chosen because they're the parts where "written carefully" and "actually correct" are the same thing without hardware in the loop.
#   A I M e d i a E d i t o r  
 