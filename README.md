# AI Media Editor — Phase 1–3: media browser, manual editor, real export, persistence

Status: **manual editor + real Media3 export pipeline (device-verified) +
project persistence/autosave (JVM-verified this round, not yet device-run).**
The AI layer (natural-language prompts, media search/indexing) is not built
yet — everything below is the conventional editor spec section 9 requires to
exist on its own, plus the non-destructive EDL/command core spec section 21
and the architecture notes require the AI layer to sit on top of later. See
"What's not here yet" for exactly what's missing, and "What's verified vs.
not" for which parts of *this* round have actually been run.

## What's actually here

**1. Media browser (Phase 1).** A home screen that queries `MediaStore`
directly once permission is granted and shows every photo/video on the
device in a grid, with a correct runtime permission flow across Android 8
through the latest granular-media/partial-access models. Tap opens a
full-screen preview; multi-select and "Create Project" hand the selected
items to the editor.

**2. Non-destructive EDL core.** `editor/model/` — `ProjectState`,
`VideoClip`, `AudioTrack`, `TextOverlay` — plus the closed `EditCommand`
taxonomy and `ProjectSanitizer`, the validation gate every command (AI- or
UI-originated) is clamped through before it can touch project state.
Nothing, including the manual editor UI itself, mutates `ProjectState`
directly — it only ever emits `EditCommand`s. Original media is never
touched; the project only stores `content://` URI references.

**3. Manual timeline editor (Phase 2).** `ui/editor/EditorScreen.kt` +
`TimelineStrip`/`TimelinePreview`/`ClipPreview`: import photos and videos,
reorder, trim, split, delete, set per-clip speed/volume/filter, add text
overlays (position + time window), add an audio track (with looping),
undo/redo via `ProjectHistory`, live preview that plays the edited sequence
(not the raw source), and aspect-ratio selection (9:16, 16:9, 1:1, 4:5) with
Smart Reframe-style focal-point cropping (`CropMath`) rather than a plain
centre crop.

**4. Real export pipeline (Phase 3).** `editor/export/CompositionBuilder.kt`
builds an actual Media3 `Composition` from the project — per-clip trim,
speed, volume, filter and crop, an audio track, and text overlays burned in
via a composition-level `OverlayEffect` (so preview and export share the
same rendering path). `ExportWorker` runs `Transformer` inside a
`WorkManager` foreground job (720p or 1080p), so it survives backgrounding,
shows real progress, and writes the result to `MediaStore` — no forced
watermark. This has been run on a real device and confirmed to produce a
playable file with the edits present, not just compiled.

**5. Project persistence and autosave (spec section 22).**
`data/project/ProjectRepository.kt` writes each project as a JSON file
(`ProjectState` + a name/timestamps wrapper, via `kotlinx.serialization`) to
app-private internal storage, one file per project, written to a temp file
and renamed into place so a crash mid-write can never leave a corrupt file
behind. `EditorViewModel` autosaves on every edit (debounced ~800ms so a
trim drag doesn't write on every frame) and flushes immediately when the
screen stops or the user navigates back, so a killed app or a crash loses at
most the in-flight debounce window, never the whole draft. Home shows a
"Projects" row and a full `ProjectsScreen` lists every saved project
(thumbnail, clip count, duration, last-updated) with rename and delete.
Opening a project loads it back into a live, editable `ProjectHistory` —
this is what makes "reopen app → recover draft → continue editing" (spec
section 22) real rather than aspirational.

## What's verified vs. not, this round

The persistence work above is new, plain-Kotlin logic with no Media3/codec
involvement, so it could actually be checked without a device: the exact
`@Serializable` model in `editor/model/ProjectState.kt` and
`data/project/ProjectRecord.kt` was copied into a standalone Kotlin/JVM
Gradle project pinned to this repo's exact Kotlin (2.3.20) and
`kotlinx-serialization-json` (1.9.0) versions, and round-tripped through
encode → decode with a project containing multiple clips (video and photo),
a null focal point, an audio track, a text overlay with non-ASCII text, and
special characters in the project name — `decoded == original` held in
every case, plus a forward-compatibility check (an unknown JSON field is
ignored, not a crash) and an empty-project case. That's a genuine,
reproducible pass, not "written carefully."

What that check does *not* cover: the Android-specific half of
`ProjectRepository` (`context.filesDir`, `File.renameTo`, WorkManager/
Compose lifecycle timing for the debounce and the ON_STOP flush), and none
of the new UI (`ProjectsScreen`, the rename dialog, Home's "Projects" row).
Those still need the real-device pass described in "Build instructions" —
this sandbox has no Android SDK, so nothing Android-specific in this round
has been compiled, let alone run. Phases 1–3 (media browser, manual editor,
export) remain exactly as previously device-verified; nothing about them
changed this round.

## What's not here yet

- **AI prompt interface** (spec sections 4–9, 21): no "Ask AI" screen, no
  LLM call, no structured edit-plan generation. `EditCommand` is the target
  shape for that output, but nothing produces it from natural language yet
  — today, every `EditCommand` comes from a UI button in the manual editor.
- **Media indexing / natural-language search** (spec section 5–6): no
  object/scene/face detection, no embeddings, no local search index.
  `MediaRepository` does a flat `MediaStore` query.
- **Photo editor** (spec section 10) beyond a still image as a timeline
  clip: no crop/brightness/filters screen for a single photo.
- **AI photo features** (background removal, smart crop as a standalone
  tool, object removal, generative fill) — none implemented.
- Text overlay styling (size/colour/font) — Media3 defaults only, no UI
  controls for them yet.
- 4K export, thermal/low-RAM device tuning, proxy/preview-resolution
  editing — untouched.

Nothing in that list has been stubbed or faked to look functional — it's
simply absent, per spec section 33's rule against pretending unfinished
features work.

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
            ├── ui/nav/AppNavGraph.kt
            ├── ui/home/{HomeScreen, HomeViewModel, MediaPreviewDialog}.kt
            ├── ui/permissions/MediaPermissionState.kt
            ├── ui/editor/{EditorScreen, TimelineStrip, TimelinePreview,
            │              ClipPreview, AddTextDialog, AddAudioDialog}.kt
            ├── ui/projects/{ProjectsScreen, ProjectsViewModel}.kt
            ├── data/media/{MediaItem, MediaRepository, AudioRepository}.kt
            ├── data/project/{ProjectRecord, ProjectRepository}.kt
            ├── editor/{EditorViewModel, MediaMapping}.kt
            ├── editor/model/{ProjectState, EditCommand, ProjectSanitizer,
            │                 ProjectHistory, CropMath, TimelinePositions}.kt
            └── editor/export/{CompositionBuilder, ExportWorker,
                                PendingExportHolder}.kt
```

Single Gradle module. Split into the `core-*`/`feature-*` layout from spec
section 16 once there's enough surface area (AI + indexing layers) that the
module boundary earns its build-time cost.

## Dependencies

| Library | Version | Why |
|---|---|---|
| Android Gradle Plugin | 9.3.0 | Latest stable as of writing |
| Kotlin | 2.3.20 | Stable, paired deliberately one minor behind literal-latest |
| Compose BOM | 2026.08.00 | Requires compileSdk 37 |
| AndroidX Navigation Compose | 2.9.7 | Home ↔ Editor navigation |
| Media3 (exoplayer, ui-compose, transformer, effect) | 1.11.0 | Playback preview + real export pipeline |
| AndroidX WorkManager | 2.10.0 | Foreground export job (architecture-notes §2.4) |
| AndroidX Lifecycle | 2.11.0 | `viewModel()` Compose helper |
| Coil | 3.3.0 (`io.coil-kt.coil3`) | Local `content://` thumbnails, no network module |
| kotlinx-serialization-json | 1.9.0 | Project (de)serialization for autosave; the Kotlin plugin variant is pinned to the Kotlin version above (guaranteed matching, not a guess) |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | targetSdk pinned one level back of compileSdk so Android 17's forced behavior changes don't land before they're deliberately handled |

## Build instructions

1. Open the `AIMediaEditor/` folder in a current Android Studio.
2. Let it sync Gradle (wrapper is committed at `gradle/wrapper/`, targeting
   Gradle 9.5.0). Accept any AGP/Kotlin bump Studio suggests if bundled
   versions differ slightly.
3. Run on a device or emulator on Android 8.0 (API 26) or newer with some
   photos/videos present (or `adb push file.jpg /sdcard/Pictures/`).

This sandbox has no Android SDK and no network path to `dl.google.com`
(Google's SDK component repository is not reachable from here, confirmed
this round), so nothing in this environment can invoke the Android Gradle
Plugin to actually compile or run the app — only Android Studio, or Claude
Code running on your machine with the SDK installed, can do that. The
manual-editor and export code in this README has been built and run
end-to-end on a real device outside this sandbox (see "Testing instructions"
below); this sandbox's own contribution this round was syncing that
already-verified source into the repository, not re-verifying it.

## APK

Build ▸ Build Bundle(s)/APK(s) ▸ Build APK(s) in Android Studio; output
lands in `app/build/outputs/apk/debug/`.

## Setup instructions for any AI API — not needed yet

No live AI call exists yet. `EditCommand` is the shape an LLM's output will
be parsed into once the prompt UI is built. When that's wired up:
- An LLM to turn a prompt into a JSON `EditCommand` list — e.g. the Claude
  API, called with a securely-stored key (never bundled into the APK).
- On-device ML (architecture-notes "Level 1") for indexing/search — e.g.
  ML Kit for face/object detection and scene labeling with no network call,
  matching spec section 15's privacy principle.

## Known limitations

- Project persistence is file-per-project JSON, not Room — fine at the
  "handful to a few dozen projects" scale an individual's device actually
  has; `ProjectsScreen`/`HomeViewModel` re-read and re-parse every file on
  each refresh, which would need to change before this scales to hundreds.
- No pagination in `MediaRepository` — fine for a few hundred items, wrong
  for a multi-thousand-item library.
- Text overlay styling is Media3-default only (no size/colour/font control).
- Volume is flat per clip/track — no fades or automation.
- Project thumbnails reuse the same "no decoded video frame yet" limitation
  as Home's grid — a video-first project shows a play glyph, not a frame.
- Single module, no DI framework.
- No automated test suite wired into the Gradle build itself
  (`app/src/test`) despite `editor/model/` and `data/project/` being pure,
  portable Kotlin that's well suited to one — this round's verification of
  the persistence model (see "What's verified vs. not") was a standalone
  script, not a checked-in, CI-runnable test.
- Not verified: performance on low-RAM devices, 4K sources, thermal
  throttling during export, HDR tone-mapping.

## Testing instructions

Manual, on a real device (no SDK in this sandbox to run instrumented tests):

1. Fresh install → permission screen, not a crash. Deny → stays on
   permission screen. Grant → media grid populates.
2. Select several photos and videos on Home → Create Project → editor
   opens with them on the timeline.
3. Trim a clip, split a clip, reorder clips, delete a clip → preview
   reflects each change; Undo/Redo reverses/reapplies it.
4. Add a text overlay with a specific time window and position → confirm it
   appears in the live preview at that window and position.
5. Add an audio track, toggle looping → confirm it plays under the video.
6. Change aspect ratio (9:16 / 16:9 / 1:1 / 4:5) → preview frame and crop
   follow the new ratio, not a fixed 16:9 box.
7. Export at 720p and at 1080p → confirm a progress notification appears,
   export survives backgrounding the app, and the resulting file in
   `MediaStore` plays with all of the above edits present — trims, speed,
   volume, filter, crop, text (at the right moments, including a text
   window that spans a clip boundary), and audio.
8. Export a project with no text/audio at all → confirm nothing regresses.
9. Create a project, make an edit, background the app (Home button) without
   exporting, then kill the app from Recents → relaunch → open it from
   Home's "Projects" row or the Projects screen → confirm the edit is still
   there (this is the actual crash-recovery claim from spec section 22;
   items 1–8 above don't exercise it).
10. Rename a project from the editor's title, and delete one from the
    Projects screen → confirm both take effect and the delete confirmation
    can be cancelled without deleting.
11. Create several projects, confirm Home's "Projects" row and the full
    Projects screen agree on what exists and show a sensible relative time
    ("Just now", "Xm ago", etc.) that updates on revisit.

## Why it stops here

The spec and architecture notes describe a system with two genuinely
separate layers: a real, stable manual editor with real export (Phase 1–3),
and an AI layer on top of it that plans and executes edits through the same
validated command taxonomy (Phase 4–5). Building the AI layer against an
editor that hadn't been proven to actually export a correct file on real
hardware would mean validating AI-generated plans against an unverified
foundation — so this round of work stayed on hardening and device-verifying
the foundation (trim/split/reorder/crop/text/audio/export) rather than
starting the prompt UI on top of it. `EditCommand` and `ProjectSanitizer`
already exist specifically so the AI layer has a validated, closed surface
to target next, without either layer needing to be rewritten to meet the
other.

This round picked project persistence for the same reason, not a different
one: it's an explicit MVP "must have" (spec section 25, items 9/12/13) that
was still missing, and unlike the AI layer it's plain Kotlin/JSON/file I/O
with no codec or device-timing behavior to get subtly wrong — exactly the
kind of foundation piece that can be genuinely checked from this sandbox
(see "What's verified vs. not") rather than only written carefully and
hoped for. The AI layer stays next, once this is confirmed on-device too.
