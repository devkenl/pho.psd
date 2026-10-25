package app.phopsd.editor

enum class Status(val label: String) {
    Done("Implemented"),
    Partial("Partial"),
    Planned("Planned"),
}

data class FeatureEntry(val area: String, val status: Status, val note: String)

/**
 * In-app mirror of the root FEATURE_MATRIX.md, shown in Help ▸ Feature Status so users
 * can see exactly what this build can and cannot do. Keep both in sync.
 */
object FeatureStatusList {
    val entries = listOf(
        FeatureEntry("Workspace shell, menus, quick strip, tool rail, dock", Status.Done, "Unbuilt commands are visible but explain their milestone"),
        FeatureEntry("New document (presets, size, ppi, background)", Status.Done, "8-bit sRGB only"),
        FeatureEntry("Open JPEG / PNG / WebP", Status.Done, "Platform ImageDecoder, EXIF orientation, converted to sRGB; large images are downsampled to fit the memory budget, with a warning"),
        FeatureEntry("Place image as layer", Status.Done, "Non-destructively fitted to canvas"),
        FeatureEntry("Raster layers: order, visibility, lock, rename, duplicate, delete", Status.Done, ""),
        FeatureEntry("Layer opacity and 16 blend modes", Status.Done, "W3C blend formulas; premultiplied float compositing"),
        FeatureEntry("Move tool, numeric position/scale/rotation", Status.Done, ""),
        FeatureEntry("Brush and eraser (soft/hard, size, opacity, flow, hardness, smoothing)", Status.Done, "Stylus pressure for size/opacity/flow; stylus eraser end; fingers navigate once a pen is used"),
        FeatureEntry("Layer masks: add, paint, disable, invert, view, apply, delete", Status.Done, "Grayscale masks in layer space, saved in projects; masks never alter layer pixels"),
        FeatureEntry("Rectangular / elliptical marquee, lasso, polygonal lasso", Status.Done, "New/add/subtract/intersect, select all, deselect, inverse; anti-aliased edges"),
        FeatureEntry("Crop (with ratios), canvas size, image size, rotate/flip canvas", Status.Done, "Non-destructive: pixels outside the canvas are kept in their layers"),
        FeatureEntry("Free transform handles", Status.Done, "Scale (proportional/centre), rotate with snapping, move, flip"),
        FeatureEntry("Cut/copy/paste, clear, fill, layer via copy/cut, merge down, flatten", Status.Done, "Internal clipboard (not shared with other apps yet)"),
        FeatureEntry("Eyedropper, foreground/background colours, colour picker", Status.Done, "Samples the visible composite"),
        FeatureEntry("Feather, refine edge, magic wand, quick selection", Status.Planned, "M6"),
        FeatureEntry("Two-finger layer move/scale/rotate (Move tool option)", Status.Done, ""),
        FeatureEntry("Pan, pinch-zoom, rotate view, fit, 100%", Status.Done, ""),
        FeatureEntry("Rotation snapping for touch gestures (15°/45°/90°/off)", Status.Done, "Magnetic: twist further to break free"),
        FeatureEntry("Undo / redo / History panel", Status.Done, "Covers every edit; pixel steps store only changed tiles; oldest steps are released past a memory budget"),
        FeatureEntry("Tiled rasters, mip previews, background rendering", Status.Partial, "CPU renderer; GPU backend and benchmarks: M7"),
        FeatureEntry("Channels panel", Status.Partial, "Composite channel thumbnails only"),
        FeatureEntry("Save / Save As / reopen .phopsd projects", Status.Done, "Written to a private temp file, verified, then copied to your chosen location"),
        FeatureEntry("Autosave and crash recovery", Status.Done, "App-private autosave; offered on the home screen after an unexpected exit"),
        FeatureEntry("Recent projects", Status.Done, "Projects you saved or opened in this app"),
        FeatureEntry("PNG / JPEG export", Status.Done, "Full resolution or scaled; JPEG flattens transparency onto white"),
        FeatureEntry("Adjustment layers, curves, levels", Status.Planned, "M5"),
        FeatureEntry("Groups, clone/heal, gradients, filters", Status.Planned, "M6"),
        FeatureEntry("Workspace presets, rulers/guides, histogram, navigator", Status.Planned, "M7"),
        FeatureEntry("PSD/PSB, RAW, ICC/16-bit, text, paths, liquify", Status.Planned, "M8+"),
    )
}

/** Keyboard shortcuts actually handled by this build (Help ▸ Keyboard Shortcuts). */
object ShortcutList {
    val entries: List<Pair<String, String>> = listOf(
        "Ctrl+N" to "New document",
        "Ctrl+O" to "Open image",
        "Shift+Ctrl+O" to "Open project",
        "Ctrl+S / Shift+Ctrl+S" to "Save / Save As",
        "Alt+Shift+Ctrl+W" to "Export PNG / JPEG",
        "Shift+Ctrl+P" to "Place image as layer",
        "Ctrl+W" to "Close document",
        "Ctrl+Z / Shift+Ctrl+Z / Ctrl+Y" to "Undo / Redo",
        "Shift+Ctrl+N" to "New layer",
        "Ctrl+J" to "Duplicate layer (Layer via Copy with a selection)",
        "Shift+Ctrl+J" to "Layer via Cut",
        "Delete / Backspace" to "Clear selection (or delete layer without one)",
        "Ctrl+X / Ctrl+C / Ctrl+V" to "Cut / Copy / Paste",
        "Shift+F5" to "Fill",
        "Ctrl+E" to "Merge down",
        "Ctrl+T" to "Free transform",
        "Ctrl+A / Ctrl+D / Shift+Ctrl+I" to "Select all / Deselect / Inverse",
        "Ctrl+I" to "Invert layer mask",
        "Ctrl+\\ / \\" to "Edit mask ↔ pixels / Mask overlay",
        "Alt+Ctrl+I / Alt+Ctrl+C" to "Image size / Canvas size",
        "B / E / I" to "Brush / Eraser / Eyedropper",
        "M / Shift+M" to "Marquee (rectangle ↔ ellipse)",
        "L / Shift+L" to "Lasso ↔ Polygonal lasso",
        "C" to "Crop (Enter applies, Esc resets)",
        "[ / ]" to "Brush size smaller / larger",
        "Shift+[ / Shift+]" to "Brush hardness softer / harder",
        "1 … 0" to "Brush opacity 10 % … 100 %",
        "X / D" to "Swap / default colours",
        "Alt + click (brush)" to "Sample colour",
        "Shift / Alt while selecting" to "Add / subtract (both: intersect)",
        "Ctrl+] / Ctrl+[" to "Bring forward / Send backward",
        "Shift+Ctrl+] / Shift+Ctrl+[" to "Bring to front / Send to back",
        "Ctrl+= / Ctrl+-" to "Zoom in / out",
        "Ctrl+0 / Ctrl+1" to "Fit on screen / Actual pixels",
        "R / Shift+R / Esc" to "Rotate view / Reset rotation",
        "V / H / Z" to "Move / Hand / Zoom tool",
        "Space + drag" to "Temporary pan",
        "Arrow keys (Shift = 10 px)" to "Nudge layer (Move tool)",
        "Mouse wheel" to "Zoom at pointer",
        "F7" to "Show / hide Layers dock",
    )
}
