# AI Media Editor — Phase 1–3: media browser, manual editor, real export, persistence

Status: **manual editor + real Media3 export pipeline (device-verified) +
project persistence/autosave (JVM-verified) + several real-device bug fixes
+ a draggable audio timeline + a timeline playhead and zoom + audio
snapping + on-canvas text positioning + real audio waveforms + a
reorderable multi-effect stack + fade-to-black transitions + a checked-in
JUnit test suite for `editor/model/` (below).** The
AI layer (natural-language prompts, media search/indexing) is not built yet
— everything below is the conventional editor spec section 9 requires to
exist on its own, plus the non-destructive EDL/command core spec section 21
and the architecture notes require the AI layer to sit on top of later. See
"What's not here yet" for exactly what's missing, and "What's verified vs.
not" for which parts of the last few rounds have actually been run.

## Adopting the "World-Class Video Editor" UX spec

A second, much larger spec was provided this round: a 48-section
professional-editor UX document (magnetic timeline, keyframe animation,
masks/tracking, color wheels/curves, multicam, transcript editing, a
command palette, chroma key, and more) — DaVinci/Premiere/CapCut-scale, not
something any single round attempts. The document itself carries its own
priority list (section 45: Tier 1 "MVP/Essential," Tier 2 "Premium Feel,"
Tier 3 "Professional"), so that's the priority this project adopts too,
rather than inventing a separate one. Progress is tracked against Tier 1
from here on; Tier 2/3 aren't started.

Naming note, since both documents number their own sections 1 onward: from
here on, "UX spec section N" means this new document; a bare "spec section
N" still means the original product spec, as everywhere earlier in this
README.

**Built against Tier 1 so far:** a visible timeline playhead and pinch-free
zoom (+/- buttons), audio drag/trim snapping to clip edges, on-canvas
direct manipulation for text position (drag the overlay on the preview
itself), real per-file audio waveforms on the audio lane, a reorderable
multi-effect stack per clip, and fade-to-black transitions between clips —
all covered under "What's actually here" below. Still missing from Tier 1:
true drag-and-drop media placement (media is added by selecting then
tapping "Add to Project," not dragged onto the timeline), and a true
cross-dissolve transition (this round's transition fades through black
rather than blending two clips' video together — see item 11 below for
why).

## Bug fixes (reported from a real device)

1. **"Add audio" showed no files despite having plenty on the device.**
   `AudioRepository` filtered on `MediaStore.Audio.Media.IS_MUSIC != 0` —
   that flag depends on scanner/tagging heuristics most real files (browser
   downloads, voice notes, anything not from a library-style music app)
   never get set. Filter removed; the audio query now matches how
   `MediaRepository`'s own image/video queries work (no such flag filter).
2. **Cropping one photo appeared to crop every photo in the project.**
   The export path was already correct (`CompositionBuilder` applies each
   clip's own `focalPoint`) — the bug was in the crop-overlay's Compose
   state: it was keyed on the *focal point value*, and two never-cropped
   photos both default to the same value (centre), so switching clips
   didn't reset the drag state and the overlay kept showing the previous
   photo's dragged position. Now keyed on the clip's id as well, in
   `ui/editor/ClipPreview.kt`.
3. **Filters appeared to do nothing.** They were only ever rendered in
   "Play Timeline" mode by design — the single-clip preview intentionally
   played the raw, unfiltered source, which looks indistinguishable from a
   filter simply not working. The single-clip preview now shows filters
   live too: a Compose `ColorMatrix` approximation for photos, and
   `ExoPlayer.setVideoEffects()` (reusing the exact same
   `CompositionBuilder.filterEffects()` export uses) for video.
4. **A tall photo/video took over the whole editing screen**, pushing every
   tool below it out of view — and dragging on it to scroll didn't work
   either, because the crop overlay's full-area drag detector was
   swallowing the gesture. The preview is now a fixed-height box
   (`PREVIEW_HEIGHT` in `ClipPreview.kt`) that letterboxes/pillarboxes the
   content instead of stretching to the source's own aspect ratio.
5. **A video clip's speed setting didn't visibly change anything.** Same
   root cause as filters (#3): `SetSpeed` updated the clip's data, and
   export already read it, but nothing told the live preview's `ExoPlayer`
   to actually change its playback rate. Fixed with
   `exoPlayer.setPlaybackSpeed(clip.speed)` — this one has no version
   uncertainty, since it's base `Player` API, not a Transformer/effects call.

None of these five have been re-verified on a device yet (this sandbox still
has no Android SDK) — the crop and audio fixes are plain Kotlin/state-key
logic with no Media3 uncertainty, the layout fix is a standard bounded-box
Compose pattern, and the video-filter live preview is the one genuinely
uncertain piece: `ExoPlayer.setVideoEffects()` is a real, documented Media3
API for exactly this, but Media3 publishes only to Google's Maven repo,
which this sandbox can't reach, so its exact signature at this version
couldn't be confirmed (flagged in a comment at the call site in
`EditorScreen.kt`). If it fails to compile, that one call is the first place
to look — the underlying `filterEffects()` mapping it reuses is unchanged
and already exercised by export.

### Follow-up: the audio timeline UI above didn't actually work at all

Reported immediately after the audio timeline round: neither dragging a
track to reposition it nor dragging its edge to trim it did anything. Two
real, concrete bugs, both in `AudioTrackStrip.kt`:

- **Layout**: the scrollable box also had an explicit `.width(totalWidth)`
  on it directly — collapsing the distinction between "how much is visible"
  (the scroll viewport) and "how wide the full timeline is" (the content),
  which `horizontalScroll` needs kept separate. Fixed by nesting: an outer
  box sized by its own parent (the scrollable viewport) containing an inner
  box explicitly sized to `totalWidth` (the content the tracks are
  positioned within) — the same shape `TimelineStrip`'s Row implicitly gets
  for free from laying its clips out sequentially, but a Box of *absolutely*
  -positioned children (needed here, since two tracks can overlap or leave
  gaps) has to be told its content width explicitly.
- **Gesture conflict**: the reposition-drag was attached to the *entire*
  track block, which fully overlapped the trim handle sitting inside that
  same block — two competing `detectDragGestures`, both real drag
  recognizers, racing for the same touches. Replaced with a dedicated
  22.dp grip (↔), separate from the trim handle, mirroring
  `TimelineStrip`'s own reorder grip, which uses exactly this
  separate-touch-target structure already.

**Also reported at the same time: video clips "not able to trim."** Video's
trim handles and reorder grip are structurally the same separate-touch-
target pattern as above and weren't touched this round — but a real overlap
was found there too: `MIN_CLIP_WIDTH` was 40.dp, while the reorder grip
(22.dp, centered) plus both trim handles (14.dp each, at the edges) need
50.dp of clearance with zero margin — so any clip at or near the minimum
width (a short photo or a tightly trimmed clip, a likely case for the
multi-photo projects this has been tested with) had its reorder grip and
trim handles physically sharing pixels, the same conflict as the audio bug
above, just conditional on clip width rather than present every time.
Raised to 64.dp. **Confirmed fixed** — trim now works on both lanes.

### Follow-up: the trim lane was a flat, unlabelled colour block

With trimming actually working, the next real problem: a video clip in the
timeline showed as a single solid colour with a duration number — no way to
tell what the clip *was*, or exactly where the current trim points landed,
short of letting go and checking. Two additions, both in `TimelineStrip.kt`:

- **A real frame from the source**, not a placeholder. New
  `data/media/VideoThumbnailLoader.kt` pulls a frame via
  `MediaMetadataRetriever` — core `android.media`, not Media3, so unlike
  some other pieces of this app its API surface isn't something this
  sandbox has been unable to verify.
- **Start → end time labels in mm:ss**, updating live while a trim handle
  is being dragged, matching what the reported problem actually was: no way
  to know the exact moment being trimmed to without releasing and checking.
  The audio track blocks got the equivalent fix for consistency (they had
  the same gap in a different shape — a duration-only label with no
  indication of *where* on the timeline the track starts) via the same new
  `formatClock()` helper.

**Follow-up to the follow-up**: one frame stretched/cropped across the
whole clip doesn't represent a multi-second clip well — it's one moment,
not "what happens in this clip." `loadFilmstrip()` now pulls several
frames (up to 8, roughly one per 40.dp of clip width — a longer clip gets
wider tiles covering more time each, rather than more extraction work) and
tiles them across the clip, each sampled from the midpoint of its own
slice of the *currently trimmed* range, so trimming the clip changes which
moments the strip shows. One `MediaMetadataRetriever` is reused for every
tile in a strip rather than one retriever per frame — re-opening the
source file is the expensive part, not decoding an individual frame from
an already-open one. Each tile fails independently (a bad frame just shows
blank in that one slot) rather than losing the whole strip over one
decoder hiccup.

Not done here: Home's media grid and the Projects list still show a play
glyph instead of a video frame (the original, still-true "no decoded video
frame yet" known limitation) — this thumbnail loader could feed those too,
but wasn't asked for and would touch separate, already-working screens, so
it's left as a natural but separate follow-up rather than pulled in here.

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
`TimelineStrip`/`TimelinePreview`/`ClipPreview`/`AudioTrackStrip`: import
photos and videos, reorder, trim, split, delete, set per-clip speed/volume/
filter, add text overlays (position + time window), add an audio track and
drag it into place — reposition by dragging the block, trim its length by
dragging its right edge, both directly on a timeline lane under the video
clips (volume presets and looping stay as toggle chips) — undo/redo via
`ProjectHistory`, live preview that plays the edited sequence (not the raw
source), and aspect-ratio selection (9:16, 16:9, 1:1, 4:5) with Smart
Reframe-style focal-point cropping (`CropMath`) rather than a plain centre
crop. Crop today is still reposition-only within one of those four fixed
ratios — a freeform, drag-to-resize crop rectangle (WhatsApp-style) is a
requested but not yet built follow-up.

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

**6. Timeline playhead and zoom (UX spec section 12, Tier 1).** The video
row and audio lane now take a shared `pixelsPerSecond` from `EditorScreen`
instead of each hardcoding its own fixed scale — a `+`/`−` `ZoomRow` above
the timeline (50%–300%, in 25% steps) changes it live, and both lanes
resize together since they read the same value. During "Play Timeline"
playback, `TimelinePreview` polls the `CompositionPlayer`'s position every
100ms and reports it up; both lanes draw a red playhead line pinned to that
timestamp. Both lanes also now share one `ScrollState` (passed down from
`EditorScreen`) rather than each creating its own — scrolling either one
moves both, which incidentally fixes the "the two lanes scroll
independently" limitation earlier rounds had flagged, since keeping the
playhead visible in both required solving exactly that. The timeline lanes
(previously hidden while "Play Timeline" was active, visible only in
"CLIP" editing mode) are now visible in both modes, since the playhead
needs somewhere to be drawn during playback, and it also better matches
the UX spec's own mental model (section 2: timeline always visible below
the preview, not swapped out for a second full-screen mode) than the
previous toggle did.

Deliberately not built alongside this: pinch-to-zoom. The UX spec asks for
it (section 41), but a pinch gesture layered over the same timeline area
that already hosts per-clip trim/reorder and per-track reposition/trim
drags is exactly the kind of overlapping-gesture risk the last two rounds'
bug reports (audio drag not working, video trim not working) came from —
adding a new multi-touch gesture on top of gestures already reported
broken once felt like the wrong moment to take that risk blind. +/- buttons
get the same outcome with no gesture-conflict surface at all.

**7. Snapping (UX spec section 8/17, Tier 1).** Dragging an audio track's
position (the reposition grip) or its length (the right trim handle) now
snaps to the nearest video clip boundary, the timeline start, or the
current playhead, within a small pixel tolerance (`snapThresholdMs`,
scaled by zoom so the tolerance stays a constant on-screen distance rather
than a constant time window). A yellow guide line appears at the point
being snapped to, per the UX spec's "show snapping guides" — silently
jumping a dragged value with no visual confirmation was already flagged
as a UX defect in an earlier round's crop-overlay bug, so this round's
snap doesn't repeat it.

Deliberately audio-only, not applied to a video clip's own trim handles:
those edit *source-relative* time (an offset into that one clip's own
file), which has no "other clip's edge" in the same coordinate space to
snap to — a video clip's trim boundary and another clip's project-timeline
position aren't comparable numbers. An audio track's `startMs` genuinely
lives in project-timeline coordinates (same space as clip boundaries),
which is what makes snapping it meaningful. Snapping to *other audio
tracks'* edges, and to markers/beats (neither of which exist in this app
yet), are both explicitly out of scope this round, not silently dropped.

**8. On-canvas text positioning (UX spec section 17, Tier 1).** A text
overlay visible in the single-clip preview (`ClipPreview`) can now be
dragged directly on the preview to move it, instead of only choosing from
3 fixed Y-position dialog presets. `TextOverlay` gained
`xPositionFraction` (defaulting to 0.5, centre, so every existing overlay
and every saved project keeps its prior on-screen position unchanged) to
go with the existing `yPositionFraction`; a new `SetTextPosition`
command carries both fractions through `ProjectSanitizer` (clamped to
`[0, 1]`) into `ProjectState`. `CompositionBuilder`'s text overlay effect
now computes an X anchor the same way it already computed the Y anchor —
`2f * fraction - 1f`, no flip needed for X since both the fraction
convention and Media3's NDC X axis point left-to-right (Y needs
`1f - 2f * fraction` because NDC Y points up while the fraction
convention points down, which was already the case before this round).

The on-canvas handle (`ClipPreview.TextOverlayHandle`) is deliberately a
drag TOOL, not a second renderer of final appearance: it shows the
overlay's text in a small bordered box positioned via `BiasAlignment` so
it can be grabbed and dragged, and reports the final fraction back via
`onTextPositionCommitted` only when the drag ends — it does not attempt
to reproduce the burned-in overlay's actual font, size, or exact
rendering. `CompositionBuilder` (export) and `TimelinePreview`/
`ClipPreview`'s existing playback surface remain the single source of
truth for what the text will actually look like, the same
one-renderer-only lesson an earlier round's aspect-ratio bug established
for cropping. Dragging only moves the visible handle live; the command
(and the actual burned-in position) only updates on drag release, so a
cancelled or accidental drag can't leave the project in a half-changed
state.

**9. Real audio waveforms (UX spec section 8, Tier 1).** The audio lane's
blocks were, until now, a solid colour with a text label only — no visual
sense of where the loud/quiet parts of a track actually are, which the
UX spec flags explicitly (and this README's own "Known limitations" had
been carrying as an open gap). `data/media/AudioWaveformLoader.kt` decodes
a track's amplitude envelope using `MediaExtractor` + `MediaCodec` (core
`android.media`, the same "stable, not Media3" category as
`VideoThumbnailLoader`'s `MediaMetadataRetriever`, not a UI-thread read of
the whole file) into a fixed number of peak-amplitude buckets covering the
source's full duration, normalized against the loudest bucket found so a
quiet recording still shows a legible shape rather than a near-flat line.
`AudioTrackStrip` decodes each track's waveform once (`LaunchedEffect`
keyed on the source URI, not re-run on every drag frame) and draws it as
vertical bars on a `Canvas` behind the existing label/grip/trim-handle
layer. Since an audio track here can only be trimmed from the end (its
start in the source file is fixed — see item 3 above and "Known
limitations"), the drawn bar count is simply a prefix of the full-source
waveform sized to the currently played fraction, not a re-decode of a
different range on every trim drag.

**10. Reorderable multi-effect stack (UX spec section 20, Tier 1).** A clip
could previously carry exactly one filter, chosen from a single-select chip
row (`FilterChip(selected = filter == clip.filter, ...)`) — applying a
second filter replaced the first rather than adding to it. `VideoClip`
gains `effects: List<FilterType> = emptyList()`, and `ApplyFilter` is
replaced by two commands: `ToggleEffect(clipId, filter)` (adds the filter
to the clip's stack if absent, removes it if present) and
`ReorderEffects(clipId, orderedFilters)` (changes the stack's order without
changing membership — `ProjectSanitizer` drops any filter in the requested
order that isn't actually applied, the same "never trust the caller's
numbers" contract every other command follows). `VideoClip.filter`, the
old single-choice field, stays in the model purely for backward
compatibility with already-saved projects and is never written to again —
`ProjectSanitizer` clears it to `NONE` the moment a clip's effects are
touched, so there's exactly one live source of truth once a clip has been
edited under the new model. A new `VideoClip.effectiveEffects()` extension
is what everything downstream actually reads: for a clip already migrated
to the new model it's just `effects`; for a clip untouched since before
this round (`effects` empty, legacy `filter` non-`NONE`) it resolves to a
one-item list built from that old value, so opening an already-saved,
already-filtered project doesn't lose or change its look.

Both rendering paths that read a clip's filter were updated to read the
whole stack, not one value, since (per this README's repeated lesson from
the aspect-ratio bug) there must stay exactly one place that decides what
a clip's filters look like, reused everywhere: `CompositionBuilder` gains
`stackedFilterEffects(filters)`, which concatenates each filter's own
`Effect` list in order — Media3 already applies a `List<Effect>`
sequentially, so stacking named filters needed no new combination logic
beyond concatenation, the exact mechanism `VIVID` already used internally
to combine `Contrast`+`Brightness` into one filter's own effect list. This
same function now backs both export and the live single-clip video
preview's `ExoPlayer.setVideoEffects()` call. The photo preview path (a
static Coil `Image`, which can't run Media3 GL effects) needed real new
math: `ClipPreview` gains `stackedColorMatrixFor`/`concatColorMatrices`,
which folds each filter's existing 4×5 `ColorMatrix` into one combined
matrix using the standard affine-composition rule (apply the first
filter's transform, then the second's, to the same pixel — the same math
`android.graphics.ColorMatrix.postConcat()` performs on the platform
class, hand-written here since Compose's `ColorMatrix` exposes no
combinator method whose exact name could be confirmed without the ability
to compile against it, the same caution `CompositionBuilder` already
documents for `HslAdjustment`).

The UI (`ClipStyleRow`) changes from single-select to multi-select: tapping
a filter chip toggles it in or out of the stack rather than replacing the
whole selection, and — deliberately avoiding a new drag gesture, per the
same reasoning that held back pinch-to-zoom — a second row of up/down
buttons appears once more than one filter is applied, to reorder the
stack without adding another touch surface to an area that's already
produced two rounds of real gesture-conflict bugs (audio drag, video
trim).

**11. Fade-to-black transitions between clips (UX spec section 25, Tier
1).** A tappable divider now sits between every pair of adjacent clips on
the timeline row — tap it to add a transition at that cut, tap again to
remove it. `ProjectState` gains its own `transitions: List<ClipTransition>`
(not a field on `VideoClip` — a transition inherently involves TWO clips,
so it's modeled like audio/text: its own top-level list, not squeezed onto
one clip), each entry naming the clip it sits AFTER and a duration
(500ms default). `ToggleEffect`'s add/remove-by-membership shape is reused
here as `ToggleTransition` for the same reason: a transition is present or
absent, not a value to overwrite. A new `ProjectState.effectiveTransitions()`
extension (the same defensive-resolution pattern as `effectiveEffects()`
above) filters out any transition whose clip was deleted or is now the
LAST clip (nothing left to transition into) — both are reachable states
after editing, and both should make the transition quietly inert rather
than crash rendering or leave a stale toggle showing as active. Splitting
a clip that has a transition after it needed one more fix: the transition
is now re-attached to the SECOND half of the split, since that's the
piece actually adjacent to the original next clip — without this, the
transition would have silently relocated to sit between the two new
halves instead of where the user had it.

**The transition itself is a "dip to black," not a cross-dissolve.** A
true cross-dissolve blends two clips' video together (both visible,
opacity-crossfading) via Media3's multi-sequence video compositor, which
this app has no confirmed usage of anywhere. Rather than guess at that
API blind, this round reuses the ONE overlay mechanism already proven to
work end-to-end on a real device — the same alpha/anchor `OverlayEffect`
machinery Phase 3's text-overlay burn-in uses, just with a full-frame
black rectangle (`CompositionBuilder.transitionOverlayTextures`, a new
`BitmapOverlay` subclass, added to the SAME composition-level
`OverlayEffect` the text overlays already ride on) instead of text, and a
continuously-varying alpha (ramping 0→1→0, linear, peaking at exactly the
cut point) instead of the binary on/off alpha text overlays use. Visually
this is: the outgoing clip fades to black, then the incoming clip fades
up from black — a real, named transition type in its own right (not a
placeholder for cross-dissolve), just not the one most people picture
first when they hear "transition."

## Hardening pass (this round)

Seven consecutive rounds shipped new, unverified Compose gesture and
Media3 surface area with no device access to actually confirm any of it.
Asked how to proceed with the one remaining Tier-1 item (drag-and-drop
media placement, which turned out to need a new in-editor media tray
built from scratch plus another cross-screen drag gesture — the same risk
category that caused two real bugs earlier this session), the call was to
pause new features and spend this round hardening what already exists
instead. Two things came out of that:

**1. A real, checked-in, `./gradlew test`-runnable suite.** Every prior
round's model verification was a standalone Kotlin/JVM script living in a
scratchpad directory outside the repo — useful for this sandbox (no
Android SDK, no network to Google's Maven), but nothing a contributor or
CI would ever actually run. `app/build.gradle.kts` now depends on JUnit4,
and `app/src/test/java/com/aimediaeditor/app/editor/model/` has two real
test classes:
- `ProjectStateSerializationTest` — the round-trip/forward-compat/
  backward-compat checks every `@Serializable` change this session has
  been verified with, now importing the REAL `ProjectState`/`VideoClip`/
  `ProjectRecord` etc. directly (not a hand-copied mirror), so these tests
  can never silently drift from what actually ships the way a
  standalone script's copy could.
- `ProjectSanitizerTest` — new coverage for `ProjectSanitizer`, whose own
  doc comment has said since round 1 that its boundary arithmetic was
  "hand-traced... verified against a throwaway Python port," never
  actually executed as Kotlin. These cases (trim clamping, split
  no-ops, effect-stack toggle/reorder including the legacy-filter
  migration, transition toggle/duration-clamp, `effectiveTransitions()`
  resolution) were first proven to pass for real against a faithful
  mirror of the exact same logic in the standalone JVM harness (21
  checks, all passing, including a dedicated regression test for the
  split+transition bug fixed a few commits ago) before being ported here
  — so there's real confidence behind them even though, like everything
  Android-touching in this project, `./gradlew test` itself couldn't be
  run in this sandbox (no Android SDK to resolve AGP against). A real
  device/CI build running `./gradlew test` gets this suite for free.

**2. An adversarial re-read of this session's riskiest untested code,**
specifically the two newest, never-used-before-this-session Android APIs:
`CompositionBuilder`'s `BitmapOverlay`-based transition rendering and
`AudioWaveformLoader`'s `MediaCodec` decode loop. This caught one real
gap: the waveform decode loop had no upper bound at all — a malformed
file or an unusual codec/stream combination that never signals
end-of-stream would have spun it forever. Fixed with a plain wall-clock
elapsed-time check inside the loop (20-second cap, `DECODE_TIMEOUT_MS`)
rather than `kotlinx.coroutines.withTimeoutOrNull` — the loop's body is
pure blocking Android-framework calls with no suspension points, so
cooperative coroutine cancellation would have had nothing to actually
interrupt; a direct elapsed-time check needs no cancellation-cooperation
reasoning to be confident it's correct. The transition-overlay code held
up on re-read with no new correctness bug found, though the pass
surfaced one minor, now-documented caveat (a one-time bitmap allocation
that could land on the main thread during preview — see "Known
limitations").

Also caught in passing while auditing this round's new symbols for the
same mistake: `EditorScreen.kt` was missing its imports for
`effectiveEffects` and `clipStartOffsetMs` (`effectiveEffects` landed
without its import in the multi-effect-stack commit two rounds ago —
already fixed in the transitions commit that followed it, but worth
naming here as a concrete example of exactly the kind of bug this
hardening pass is meant to catch: brace/paren balance alone, this
project's only prior sanity check with no compiler available, cannot
catch a missing import).

**3. Two more pure-Kotlin files got the same treatment, on a follow-up
"continue."** `CropMath.kt` (off-center crop/reframe geometry) carries the
exact same "hand-traced against edge cases... verified against a
throwaway Python port, never actually executed as Kotlin" history as
`ProjectSanitizer` did, and `ProjectHistory.kt` (undo/redo, spec section
27) had been manually tested on-device since Phase 2 but never covered by
any script or suite. Both were mirrored into the standalone JVM harness
first (15 more checks: `computeCropWindow`/`toNdcCrop` across multiple
source/target/focal-point combinations including edge-pinned focal
points and degenerate zero/negative aspect ratios; `ProjectHistory`
across single-step and multi-step undo/redo, the no-op-isn't-recorded
case, and the fresh-edit-clears-redo case), all passing, then ported as
`CropMathTest` and `ProjectHistoryTest` alongside the first two test
classes. `app/src/test/java/.../editor/model/` now has four test classes
covering everything in that package except `TimelinePositions.kt`
(trivial enough — one function, already exercised indirectly through the
transition tests above — that a dedicated suite wasn't judged worth
adding this round).

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

The audio timeline UI's data model (`AudioTrack.durationMs`,
`effectiveDurationMs`) was re-verified the same way: the updated
`@Serializable` shape was round-tripped again in the same standalone
project, including a project saved *before* `durationMs` existed (no such
key in the JSON at all) to confirm it still decodes cleanly using the
default rather than crashing — that passed. What's genuinely new risk and
NOT verified: `CompositionBuilder.buildAudioSequence` now applies a
`MediaItem.ClippingConfiguration` to trim an audio track's played length
(mirroring how `buildVideoItem` already trims video, which the "Bug fixes"
section above didn't need to touch) instead of relying on `setDurationUs`
alone, which is documented as a fallback for sources whose length can't be
read from the file itself — for a real audio file, unlikely to actually
truncate playback. The video-side pattern this mirrors is already
device-verified; the audio-side application of it is not.

The playhead/zoom work has no serialization surface (`zoomFactor` and
`timelinePositionMs` are both transient UI state, never persisted), so
there was nothing to round-trip in a standalone project the way the two
checks above worked. It's plain Compose layout/state plus one Media3 read
(`CompositionPlayer.currentPosition`, a base `Player` property, not an
unstable/experimental one) — lower risk than most of this app's Media3
integration points, but still unverified on a device: specifically whether
sharing one `ScrollState` across two independent `horizontalScroll`
containers behaves as expected (a supported, documented pattern, but this
app's own history this round is "assumed a Compose layout/gesture pattern
would just work, shipped it, was wrong" three separate times — see "Bug
fixes" above), and whether the 100ms poll reads as smooth rather than
visibly stepping.

Snapping is the same kind of risk as the playhead/zoom work — no
serialization surface, pure Compose arithmetic (`snapToNearest` is a plain
function over `Long`s and was traced by hand against the same three cases
that matter: a value already exactly on a snap point, a value just outside
the threshold that should NOT snap, and a value just inside it that
should), but the actual *feel* of a snap catching correctly mid-drag on a
touchscreen is exactly the category of thing this round's own bug fixes
above were wrong about from static reading alone. Treat it as unverified
until confirmed on-device, same as the playhead/zoom work.

On-canvas text positioning has a genuine serialization surface
(`TextOverlay.xPositionFraction`), and that part was re-verified the same
way as the two checks above: the updated `@Serializable` shape was
round-tripped again in the standalone JVM project, including a project
saved *before* `xPositionFraction` existed (no such key in the JSON) to
confirm it decodes using the 0.5 default rather than crashing — both
passed. What's NOT verified: the Compose gesture and `BiasAlignment`
positioning code in `ClipPreview.TextOverlayHandle`, and the new
`anchorX` math in `CompositionBuilder`. The X-anchor formula is the exact
same shape as the already-working Y-anchor formula (just without the
flip, per the NDC-axis-direction reasoning above), which is why it's
lower risk than a from-scratch formula would be — but "the same shape as
code that works" is exactly what was true of the audio lane and video
trim gesture code before those turned out to be broken by a competing
gesture detector, so this is not being called verified on that basis
alone. Needs an on-device check: drag a text overlay to several positions
(centre, near each edge/corner), confirm the exported video burns the
text in at the same position the on-canvas handle showed.

The waveform work is the least-verifiable-from-this-sandbox piece of this
round: `MediaCodec` decode loops (as opposed to `MediaMetadataRetriever`
frame grabs, which every earlier thumbnail feature used) are new
territory for this project, and there's no way to actually run one here —
no Android SDK, no device, no emulator. The implementation follows the
standard Android `MediaExtractor`/`MediaCodec` decode-loop shape (queue
input buffers until end-of-stream, drain output buffers, release both),
but "follows the standard shape" is a much weaker claim than the
round-trip/backward-compat checks the persistence and text-position work
above actually got — this genuinely has not been exercised at all, not
even the plain-Kotlin-logic kind of check the JVM harness gives
serialization changes (`MediaCodec` requires the Android platform, so it
can't run in that harness either). One real bug was already caught by
static reading alone: `MediaFormat.containsKey()` is API 29+ while this
app's `minSdk` is 26, which would have crashed on API 26–28 devices — the
code now uses a `try`/`getLong()` pattern instead, but the fact that this
type of bug was sitting in the first draft is itself a reason to treat
the rest of this file with the same suspicion until it's actually run.
Needs an on-device check before it's trusted: does a real audio file
(not just short clips — a multi-minute track too) decode without
hanging or OOM-ing, does the drawn shape actually resemble the audio's
loud/quiet structure, and does trimming a track's length live-update
the visible bar count correctly.

The multi-effect stack has both a serialization surface and new
plain-Kotlin arithmetic, and each was verified the way this project
verifies what it actually can from this sandbox. `VideoClip.effects` was
round-tripped in the same standalone JVM project as every other
`@Serializable` change this session, including a project saved *before*
`effects` existed (only the legacy `filter` key present, no `effects` key
at all) — confirmed it decodes with `effects` defaulting to `emptyList()`
and the legacy `filter` value left untouched, rather than crashing or
losing data. The new `concatColorMatrices` math (combining two clips'
worth of filters into one 4×5 matrix for the photo preview) was hand-
traced against a plain-Python re-implementation of the same formula,
checked against sequential application of two and three stacked filters
to a sample pixel (apply filter A, then filter B, to a pixel vs. the
single combined matrix applied once) — they matched exactly in both
cases, plus the identity-matrix edge cases (`concat(identity, X) == X`
and `concat(X, identity) == X`). That's a genuine, reproducible check of
the arithmetic itself, the same caution level this README already applies
to `ProjectSanitizer`'s clamp math.

What that does NOT cover: whether `ExoPlayer.setVideoEffects()` actually
applies a multi-item `List<Effect>` the way `stackedFilterEffects`
assumes (concatenation of independently-correct per-filter lists is a
reasonable expectation of Media3's own effect pipeline, not a novel
claim, but still unconfirmed against the pinned 1.11.0 artifact — the
same honest-risk category `EditorScreen`'s live-preview `LaunchedEffect`
already flags for the single-filter case this replaces), and the new
`ClipStyleRow` UI (multi-select chip toggling, the up/down reorder
buttons) is untested Compose interaction code, same as every UI change
this session. Needs an on-device check: apply two or more filters to one
clip, confirm both the single-clip preview (video AND photo) and the
final export show all of them stacked in the chosen order, then reorder
them and confirm the visible result actually changes to match.

Transitions have a genuine serialization surface (`ProjectState.transitions`)
and genuine arithmetic (the alpha-ramp formula), and both were checked the
way this project checks what it actually can from this sandbox.
`ClipTransition` was round-tripped in the same standalone JVM project as
every other `@Serializable` change this session, including a project
saved *before* the field existed at all (no `transitions` key in the
project object) — confirmed it decodes with `transitions` defaulting to
`emptyList()` rather than crashing. The alpha-ramp formula itself (`alpha
= 1 - abs(positionMs - boundaryMs) / halfWindowMs`, clamped to 0 outside
the window) was hand-checked against its own boundary cases: exactly at
the cut point (should be 1, fully black), exactly at either edge of the
transition window (should be 0), and a quarter of the way through one
side (should be 0.5) — all match the intended symmetric dip-to-black
shape by direct substitution, the same lightweight-but-real check level
`ProjectSanitizer`'s clamp math and `concatColorMatrices` above got.

What that does NOT cover, and this is this round's biggest area of
genuine uncertainty: whether `BitmapOverlay` — a new Media3 class for
this app, never used before this round — actually renders a bitmap that
covers the WHOLE output frame the way `transitionOverlayTextures`
assumes. The text-overlay mechanism this reuses (alpha via
`StaticOverlaySettings`, composited via `OverlayEffect`) is genuinely
device-verified from Phase 3 — but that confirms the anchor-POINT
positioning of a small overlay (text), not that a same-size-or-larger
bitmap centred with no scale applied reliably fills the entire frame
regardless of the export's actual resolution. The 1920×1080 bitmap size
was chosen specifically to be at least as large as anything this app
exports (720p or 1080p, see `ExportWorker`) so the overlay doesn't need
`StaticOverlaySettings`' scale semantics to work correctly at all — but
if the true pixel-to-frame mapping this Media3 version uses turns out to
work differently than assumed, the visible failure mode would be a black
rectangle that doesn't fully cover the frame during a transition (a
partial or off-center dip to black), not a crash. Needs an on-device
check before this is trusted: trigger a transition between two clips and
confirm the ENTIRE visible frame goes black at the cut point, edge to
edge, not just a portion of it.

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
            │              ClipPreview, AudioTrackStrip, AddTextDialog,
            │              AddAudioDialog}.kt
            ├── ui/projects/{ProjectsScreen, ProjectsViewModel}.kt
            ├── data/media/{MediaItem, MediaRepository, AudioRepository,
            │               VideoThumbnailLoader, AudioWaveformLoader}.kt
            ├── data/project/{ProjectRecord, ProjectRepository}.kt
            ├── editor/{EditorViewModel, MediaMapping}.kt
            ├── editor/model/{ProjectState, EditCommand, ProjectSanitizer,
            │                 ProjectHistory, CropMath, TimelinePositions}.kt
            └── editor/export/{CompositionBuilder, ExportWorker,
                                PendingExportHolder}.kt
    └── src/test/java/com/aimediaeditor/app/editor/model/
        ├── ProjectStateSerializationTest.kt
        ├── ProjectSanitizerTest.kt
        ├── CropMathTest.kt
        └── ProjectHistoryTest.kt
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
4. `./gradlew test` runs the JVM unit test suite under `app/src/test`
   (`editor/model/`'s serialization and `ProjectSanitizer` coverage — see
   "Hardening pass" above) — no device or emulator needed for this one,
   just the Android SDK for Gradle to resolve AGP against.

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
- On-canvas text positioning supports drag-to-move (X and Y) only — no
  on-canvas resize or rotate handle for text yet, and dragging near a
  preview edge can push the handle's own bounding box (not just the text's
  anchor point) outside the visible frame since the handle isn't clamped
  to keep its full width on-screen, only its anchor fraction to `[0, 1]`.
- Volume is flat per clip/track — no fades or automation.
- Trimming an audio track's length only trims from the end, always starting
  at the source's own beginning — there's no way yet to skip past the start
  of a song (drag its left edge) the way a video clip's left trim handle
  already works. (The video row and audio lane no longer scroll
  independently — they now share one `ScrollState` — but this trim-only-
  from-the-end gap is still open.)
- No pinch-to-zoom, no tap-to-seek on the playhead — zoom is +/- buttons
  only, and the playhead is display-only during "Play Timeline" playback,
  not draggable to scrub (both are the UX spec's Tier 1 asks; the
  README's "Adopting the UX spec" section explains why pinch was held
  back this round specifically).
- Snapping only applies to audio track drags, only against video clip
  boundaries/timeline start/the playhead — not to a video clip's own trim
  handles (different coordinate space, see "What's actually here"), not to
  other audio tracks' edges, and not to markers/beats (neither exists yet).
- Crop is reposition-only within 9:16/16:9/1:1/4:5 — no freeform,
  drag-to-resize crop rectangle yet (requested, not yet built).
- The timeline strip's video filmstrip (`VideoThumbnailLoader`) has no
  cache beyond one composable's own `remember` — switching away from a clip
  and back re-extracts every tile rather than reusing them, and a very
  large source file may take a visible moment to fill in. Up to 8 frames
  are extracted per clip (see the README's filmstrip note) — fine for a
  handful of clips on screen, not something stress-tested against a very
  long timeline with many clips visible at once. Home's media grid and the
  Projects list still show a play glyph instead of a frame — this loader
  isn't wired into either yet.
- Audio waveforms (`AudioWaveformLoader`) have the same no-cache-beyond-
  `remember` limitation as the video filmstrip above, plus its own new
  ones: the full source file is decoded synchronously on a background
  thread, so a very long audio source could take a visible moment before
  its waveform appears; there's no cache shared across reopening the same
  project either. The decode loop now gives up and returns null past a
  20-second wall-clock cap (added during this round's hardening pass,
  see below) rather than running unbounded, but a file that's merely slow
  rather than hung will still show no waveform if it doesn't finish
  within that window. The waveform is a peak-per-bucket envelope, not a
  true min/max or RMS rendering, so very short transient spikes in a
  bucket's time range can look more prominent than the track's overall
  loudness in that region would suggest.
- The multi-effect stack has no per-effect intensity control (each filter is
  all-or-nothing, same as before) and no on-canvas indication of WHICH
  filters are applied besides the chip row's selected state and the order
  row's numbered list — no live thumbnail-per-filter or similar preview.
  Reordering is up/down buttons, not drag (deliberate, see "What's
  actually here" item 10), which is more taps for a stack of more than a
  couple of filters.
- Transitions are fade-to-black only, not a true cross-dissolve (see item
  11 in "What's actually here" for why), fixed at one type
  (`TransitionType.FADE_TO_BLACK` is the only entry in the enum), and the
  timeline toggle only ever adds one at the default 500ms duration — the
  `SetTransitionDuration` command exists in the taxonomy but has no manual
  UI control yet (a short/medium/long preset row, matching the pattern
  the speed/volume chips already use, is the natural next step). A
  transition also can't be shorter than either of its two neighbouring
  clips are long, in the sense that nothing currently prevents setting a
  transition duration longer than a very short adjacent clip's own
  length, which would make the dip-to-black window extend into or past
  that clip's trim boundaries — not yet guarded against. Two transitions
  close enough together that their fade windows overlap (e.g. a very
  short clip with a transition on both sides) will show both overlays'
  alpha stacked rather than anything smarter, since each transition is
  computed independently with no awareness of the others. Also noted
  during this round's hardening pass: `transitionBlackBitmap`'s one-time
  1920×1080 allocation happens lazily on whichever thread first triggers
  a transition render, which could be the main thread if that's a preview
  (as opposed to export, which runs on a background `WorkManager` thread)
  — a small (likely sub-frame, unmeasured) one-time hitch, not moved to a
  background pre-warm since that would be a bigger change than this
  round's hardening scope.
- Single module, no DI framework.
- `app/src/test` now exists (see "Hardening pass" below) but only covers
  `editor/model/` — the pure, portable Kotlin files, the same scope every
  standalone JVM verification script this project has used all along was
  already limited to (four test classes now: serialization,
  `ProjectSanitizer`, `CropMath`, `ProjectHistory`). Everything
  Android-touching (`data/media/`, `data/project/`, all of `ui/`,
  `editor/export/`) still has zero automated coverage; only
  `./gradlew test` (JVM unit tests) is wired up, not
  `./gradlew connectedAndroidTest` (instrumented, needs a device/emulator)
  — this sandbox can run neither, so even the new suite is unexecuted here,
  same caveat as everything else Android-specific in this project.
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
9a. Open "Add audio" → confirm it lists real audio files from the device
    (this was the round-4 bug: it showed none before).
9b. Select a photo, drag the crop overlay, then select a different
    never-cropped photo → confirm the second photo's overlay is centred,
    not showing the first photo's dragged position.
9c. Select a filter on a photo, then on a video → confirm the single-clip
    preview visibly changes for both (photo: immediately; video: check it
    doesn't restart playback when the filter is applied). Tap a second,
    different filter chip → confirm it ADDS to the look (both filters'
    effects visible together) rather than replacing the first, and that
    the chip row now shows both chips selected, not just the most recent
    one. Tap either selected chip again → confirm it removes just that
    filter, leaving the other applied.
9d. Select a tall portrait photo or video → confirm the preview stays a
    fixed height, doesn't cover the screen, and the page still scrolls to
    reach the editing tools below it.
9e. Select a speed preset on a video clip → confirm playback in the
    single-clip preview actually speeds up/slows down, not just the value
    shown on the chip.
9f. Add an audio track, then drag its block on the timeline lane to a new
    position and drag its right edge to shorten it → confirm both persist
    after Undo/Redo and after Export (the exported file's music starts and
    stops where you dragged it, not just where it visually looked right in
    the editor — see the README's verification caveat on the audio-side
    `ClippingConfiguration` change).
9g. Select a video clip on the timeline → confirm several distinct frames
    tile across the clip (not one frame stretched across it, not a solid
    colour block), and the label under it reads as a start → end time
    range, not just a duration. Drag a trim handle and confirm the time
    label updates live as you drag, then release and confirm the filmstrip
    refreshes to the new trimmed range. Trim a clip much shorter → confirm
    it still shows at least one tile and doesn't crash or go blank.
9h. Tap the `+`/`−` buttons above the timeline → confirm both the video row
    and audio lane resize together, staying aligned (a clip's edge and an
    audio block's edge that lined up before still line up after). Tap `+`
    to the top of its range and confirm it stops responding/disables rather
    than continuing past 300%; same for `−` at the bottom.
9i. Scroll the video row horizontally → confirm the audio lane scrolls with
    it (and vice versa) — they should no longer be independent.
9j. With clips on the timeline, tap "Play Timeline" → confirm a red
    playhead line appears on both lanes and moves smoothly (not visibly
    stepping) as the project plays, staying at the same horizontal position
    in both lanes. Tap "Back to Editing" → confirm the playhead disappears
    (CLIP mode has no single project-wide position to show one at).
9k. Add an audio track, then drag its reposition grip near a video clip's
    boundary (or the timeline start) → confirm it snaps into place (a
    yellow guide line appears at the snap point while dragging) rather than
    landing at an arbitrary pixel offset. Drag far from any boundary →
    confirm it does NOT snap and the guide line doesn't appear. Do the same
    with the right trim handle (dragging the track's end near a boundary).
    Cancel a drag partway (if your test setup allows it) → confirm the
    yellow guide clears rather than staying stuck on screen.
9l. Add a text overlay, select the clip it's on, then drag the text handle
    around the preview to several positions (centre, near each edge and
    corner) → confirm it moves smoothly and stays where dropped. Confirm
    Undo/Redo reverses/reapplies the position change. Export → confirm the
    burned-in text lands where the on-canvas handle showed, not back at a
    default position.
9m. Add an audio track from a real music/voice file → confirm its block on
    the audio lane shows actual vertical bars (not a solid colour) whose
    heights vary with the track's loud/quiet parts, appearing within a
    reasonable moment of adding it (not staying blank). Drag its right trim
    handle shorter → confirm the visible bar count shrinks to match rather
    than staying the same or showing stale bars. Try a short clip and a
    long (multi-minute) file → confirm neither hangs, crashes, or leaves
    the app unresponsive while decoding.
9n. Select a video clip, apply 2-3 filters in a row → confirm the "Order"
    row appears below the chip row, listing them numbered in the order
    applied. Tap an up/down button on one → confirm its position in the
    list changes AND the live preview's look visibly changes to match
    (a Cinematic-then-Vivid look should look different from Vivid-then-
    Cinematic once reordered). Untoggle one filter chip → confirm it
    disappears from both the chip row's selection and the Order row, and
    the Order row disappears entirely once only one (or zero) filters
    remain applied. Undo/Redo → confirm both the effect set and its order
    reverse/reapply correctly. Export → confirm the exported video shows
    all applied filters stacked in the same order shown in the editor, for
    both a video clip and a photo.
9o. With at least two clips on the timeline, tap the small divider between
    them → confirm it turns highlighted/filled (active state). Play
    through that point (either "Play Timeline" or export the project) →
    confirm the WHOLE frame edge-to-edge dips to black right at the cut,
    not a partial rectangle or an off-center patch (this is the specific
    thing "What's verified vs. not" flags as unconfirmed — the whole
    point of this test case). Tap the same divider again → confirm it
    returns to its inactive state and the cut plays as a plain hard cut
    again, no residual black flash. Split a clip that has an active
    transition after it → confirm the transition still plays between the
    (now-second-half-of-the-split) clip and its original next neighbour,
    not in the middle of the two new halves. Delete the clip a transition
    is attached to → confirm nothing crashes and the divider before the
    deleted clip's old position no longer shows as active.
10. Create a project, make an edit, background the app (Home button) without
    exporting, then kill the app from Recents → relaunch → open it from
    Home's "Projects" row or the Projects screen → confirm the edit is still
    there (this is the actual crash-recovery claim from spec section 22;
    items 1–8 above don't exercise it).
11. Rename a project from the editor's title, and delete one from the
    Projects screen → confirm both take effect and the delete confirmation
    can be cancelled without deleting.
12. Create several projects, confirm Home's "Projects" row and the full
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
