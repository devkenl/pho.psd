# pho.psd — Tablet Interface & Interaction Specification

## Canonical image
`../design-reference.png` is the primary visual reference. It shows a landscape tablet, nearly black workspace, subdued dark-navy panels, thin dividers, bright-blue selected states, top editing categories, a compact left tool rail, a large mountain image canvas, right Layers panel and a contextual brush strip along the bottom. **Do not put the mountain image into the real app as permanent artwork.** Use it only for visual validation with an optional test asset.

## Visual design tokens (initial, tune against screenshot)
| Purpose | Hex |
|---|---|
| App background | `#0C111A` |
| Primary chrome | `#151D2A` |
| Tool/nav rail | `#121B28` |
| Menus | `#111A26` |
| Dock panels | `#151F2C` |
| Raised panel | `#1E2A3A` |
| Accent | `#087CFF` |
| Hover/secondary accent | `#2196FF` |
| Main text | `#F4F7FC` |
| Secondary text | `#9DAABD` |
| Dividers | `#2A3544` |

Keep typography neutral, small but legible; consistent professional line icons (Lucide or original vectors). No bright purple theme, excessive gradients, oversized rounded mobile cards or bloated spacing. Meet touch and accessibility minimums even with visually compact controls.

## Workspace regions
1. **Application header:** Back/projects, icon + `pho.psd`, tappable current document name, unsaved marker, Undo, Redo, Save/sync state, primary blue Export, overflow/settings.
2. **Traditional menu bar:** `File Edit Image Layer Select Filter View Window Help`. Functional dropdowns/submenus. Menu commands must dispatch to the same editor command system as shortcuts and toolbar buttons.
3. **Quick category strip:** `Edit Adjust FX Filters Layer Select` with icon and selected state. It's contextual navigation, not a redundant replacement for the full menus.
4. **Left tool rail:** grouped Select, Move, Brush, Healing, Clone, Erase, Crop, Transform, Gradient, Text, Adjust, Mask, Hand, Zoom, More. Long press or secondary click for tool families. Selected tool outlined/filled blue.
5. **Canvas viewport:** occupies largest region; optionally dimmed neutral pasteboard, viewport pan/zoom/rotation independent of image data.
6. **Right dock:** `Layers | Channels | Paths` as primary tabs; additional Properties, History, Adjustments, Histogram, Navigator, Brushes, Color and Swatches accessible from Window. Resize/collapse dock; maintain visible central canvas.
7. **Context toolbar near bottom:** varies by tool. For Brush: tip/preset, Size px, Opacity %, Flow %, Hardness, More, dismiss. Update effect immediately.
8. **Subtle status strip:** zoom %, document pixel dimensions, working space/profile, document save state; optionally hide on smaller tablets.

## Top menus — content organization
- **File:** New/Open/Recent/Save/Save As/Import/Place/Document Info/Export/Close/Preferences.
- **Edit:** Undo/Redo/Cut/Copy/Paste/Fill/Stroke/Free Transform.
- **Image:** Image Size/Canvas Size/Rotate/Crop/Adjustments/Mode/Assign or Convert Profile.
- **Layer:** New/Duplicate/Delete/Rename/Group/Mask/Blend/Transform/Merge/Flatten.
- **Select:** All/Deselect/Inverse/Feather/Expand/Contract/Color Range/Select Subject.
- **Filter:** Blur/Sharpen/Noise/Distort/Camera RAW/Liquify (disable until real implementation).
- **View:** Fit/100%/Zoom/Rotate/Rulers/Guides/Grid/Snap.
- **Window:** Workspace presets and dock panels.
- **Help:** Shortcuts/Documentation/About/Diagnostics.

## Layers dock behavior
- Top: blend mode dropdown, opacity slider+numeric input, context commands; add layer, group, mask, adjustment, delete.
- Rows: eye, thumbnail, label, type subtitle, mask thumbnail where relevant, lock state, overflow. Highlight selection in blue, reorder using clear drag handles or sustained drag.
- Support rename, multi-select, show/hide, delete/duplicate, lock, reorder, opacity, blend mode and grouping as implementation progresses.
- Use lazy virtualization for large documents; never decode all layer thumbnails on main thread.
- Channels: RGB composite + R/G/B plus alpha/saved selections; Paths: editable vector paths after MVP. Keep tabs accessible but mark unbuilt actions disabled honestly.

## Canvas input
- Pinch zoom, two-finger pan; smooth inertial viewport interaction where appropriate; zoom to fit and actual pixels.
- Stylus one-contact paint, fingers navigate when stylus present; optional finger painting preference.
- Respect Android palm rejection and supported pressure/tilt/hover signals; no assumption every pen has them.
- Mouse wheel zoom, right-click contextual menus, keyboard focus and shortcut handling.
- Precise transforms: screen <-> viewport <-> document <-> layer coordinate conversions in shared math module.
- Overlay selection marching ants, transform handles and rulers without burning overlays into exports.

## Shortcut defaults
Ctrl+N New; Ctrl+O Open; Ctrl+S Save; Ctrl+Z Undo; Ctrl+Shift+Z Redo; Ctrl+T Free Transform; Ctrl+J Duplicate Layer; Ctrl+D Deselect; B Brush; V Move; E Eraser; M Marquee; L Lasso; C Crop; H Hand; Z Zoom; Space+Drag temporary pan. Test keyboard variants/platform behavior.

## Start/home
A restrained desktop-like file browser: logo, New Project, Open Image, Open Project, recent thumbnails/list, project dimensions and last modified. New Project dialog with dimensions, orientation, resolution, background type, RGB mode and presets (HD, 4K, 1:1, 4:5, A4, custom). No fake recents.

## Responsive tablet behavior
Use adaptive pane sizing, display density and available dp dimensions; fit landscape 10–14 inch tablets and modern aspect ratios. Right dock and contextual strips may collapse. No hardcoded 1408×1056 geometry or guaranteed OS navigation bar dimensions. Support resizing/multitasking where possible, and keep control focus and drag targets predictable.

## Design validation
Capture emulator screenshots after implementing each region; compare reference for spacing, hierarchy, blue selection states, usable canvas percentage and panel density. Use real, self-owned demo images only in test datasets.
