# pho.psd — Rendering, Document & Storage Architecture

## Architecture
Compose represents editor state and tools; image processing must not depend on Compose. Start with a correct separable renderer; introduce C++/NDK + GPU accelerators where beneficial. Modules (logical, not necessarily one Gradle module each): `app`, `feature-home`, `feature-editor`, `feature-workspace`, `feature-layers`, `feature-tools`, `feature-adjustments`, `core-document`, `core-renderer`, `core-io`, `core-native`, `core-ui`, `benchmarks`.

## Document graph
Stable document ID and pixel dimensions; image working color space, DPI metadata, layers in deterministic order; each layer has stable ID, name, visible, locked, opacity, blend mode, transform and optional mask. Node kinds: raster, group, adjustment initially; later vector, text, linked asset/smart-object equivalents. **Don't keep full-sized bitmap byte arrays in immutable Compose state.** Store native/tiled raster references separately. Adjustment nodes store operation name, typed parameters and optionally masks. Selections are raster mask-like state independent of destructive pixel edits.

## Rendering math
- Separate screen, viewport, document and layer spaces. Centralized forward/inverse transform utilities; test crop/pan/zoom/rotation and scaled layer hit-testing.
- Correct alpha source-over compositing; define the use of premultiplied alpha consistently. Ensure blenders with transparency and isolated groups produce correct results.
- Initial blend modes: Normal, Multiply, Screen, Overlay, Soft Light, Hard Light, Darken, Lighten, Color Dodge/Burn, Difference, Exclusion, Hue/Saturation/Color/Luminosity (ship incrementally and test numerical outputs).
- Preview and export should use the same operation graph with resolution-specific scheduling, not separate contradictory filter implementations.
- Document rendering has dirty rectangles/tiles and cached intermediate nodes; invalidate only affected nodes. Handle soft masks and large blur halos correctly at tile boundaries.
- Store undoable commands; for raster strokes save changed tile deltas or non-destructive operation records rather than full-document clones. Make eraser and painting on masks deterministic.

## Performance
- Image tiling, mip/downsample previews, viewport-aware jobs, LRU memory budgets, GPU texture pooling, cancellation and background decoding.
- Main/UI thread must not do full-size decoding, saving, long-running filtering or compression.
- Target smooth 60 FPS pan/zoom on appropriate hardware, responsive brush feedback, no casual OOM on 12/24/50 MP assets. Measure; targets not promises.
- Test on roughly 12 GB RAM premium tablets and constrained emulator/device profiles; support memory pressure callbacks. Raster tile sizes and cache budgets configurable and benchmarked; no magic unvalidated default.
- Profile viewport latency, stroke input latency, adjustment preview, open, save, export, peak RAM, GPU memory and crash incidence.

## Color fidelity
Start with sRGB 8-bit JPEG/PNG decoding with embedded profiles retained. Make internal processing precision explicit (ideally float where practical for adjustment math), ensure any alpha blending assumptions are valid. Later add color conversion via verified LittleCMS integration, Display P3 / Adobe RGB, ICC export, 16-bit assets, soft proof and tone mapping. Clearly distinguish encoded RGB vs linear-light math and choose behavior per operation; preview/export match. Do not claim accurate broad-gamut output without measured tests.

## Algorithms / tools
- Brush: size, opacity, flow, hardness, spacing, pressure/smoothing; dab interpolation, opacity accumulation and stable brush coordinate transformations. Support mask painting. Further tip texture, scatter, tilt, dynamics later.
- Selection: rectangle/ellipse/lasso represented as raster mask; selection arithmetic add/subtract/intersect/invert; feather/expand later. Operate correctly after transforms.
- Adjustments: exposure, brightness/contrast, saturation, temperature/tint, levels, RGB curves and Hue/Saturation in milestones. UI parameters numerical, undoable and serialized; curve editor with draggable points and per-channel curves.
- Crop/transform: preserve originals when possible, precision preview and editable numeric transform.
- Retouch: clone stamp current/sampled composite; healing later with sampled layer behavior. Non-destructive retouch on blank overlay layer.
- Filters: modular operation node parameters with cancel/commit and accurate export; start Gaussian blur, noise, sharpening once core stable.

## Native .phopsd container
A versioned ZIP-like container with a JSON or binary manifest recording document, layers, transforms, adjustment graph, masks, color metadata, asset IDs and thumbnails. Assets stored separately, integrity checks and schema migration strategies. Atomic write to temp file followed by validated replacement when supported by destination; respect Android SAF semantics and recovery limitations. Test save/reopen round trips, interrupted saves, dangling asset refs and project migrations. Autosave recovery must never destroy the last known good copy. Never overwrite user source photographs on import.

## File I/O roadmap
First: JPEG, PNG, WebP import/export where platform encoder capabilities verified. Respect EXIF orientation, pixel aspect and color metadata. Later TIFF/HEIC/AVIF, RAW demosaic and lens processing, and PSD/PSB. PSD compatibility is a substantial separate project: flattened preview import is not full layer fidelity. Publish field-by-field compatibility (layers/masks/groups/text/smart objects/blends/adjustments/profiles) and round-trip tests. Consider OpenRaster as an intermediate layered interchange format.

## Libraries / licensing
Evaluate modern Android ImageDecoder/BitmapFactory when suitable, Skia, OpenGL ES/Vulkan, libvips, LibRaw, LittleCMS and other libraries individually for ABI compatibility, thread safety, APK size, license obligations, cache/performance and maintenance. Avoid automatically pulling in every library. Preserve a renderer backend abstraction so a CPU correctness reference is available before GPU optimization.

## Test plan
Golden render tests for alpha/blend/curves/masks and tile boundaries; serialization round-trip; command undo-redo; document-space transforms; input strokes under zoom; ICC/profile consistency; exported image pixel/dimension checks; document recovery under interrupted I/O. Add performance benchmark scenes 12 MP/24 MP/50 MP, 20/50 layers, soft masks and adjustment graphs. Report *measured* results.
