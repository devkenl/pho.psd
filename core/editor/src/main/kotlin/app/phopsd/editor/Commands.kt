package app.phopsd.editor

/**
 * Every user-invokable action. Menus, the quick strip, the tool rail, panel buttons and
 * keyboard shortcuts all dispatch these ids into one handler, so behaviour and
 * enabled/disabled state are identical everywhere.
 *
 * [planned] is non-null for commands that are not implemented yet; those can never
 * become enabled by accident.
 */
enum class CommandId(
    val label: String,
    val shortcut: String? = null,
    val planned: Availability.Planned? = null,
) {
    // File
    NewDocument("New…", "Ctrl+N"),
    OpenImage("Open Image…", "Ctrl+O"),
    OpenFromPhotos("Open from Photos…"),
    OpenProject("Open Project…", "Shift+Ctrl+O"),
    Recent("Recent Projects…"),
    Save("Save", "Ctrl+S"),
    SaveAs("Save As…", "Shift+Ctrl+S"),
    PlaceImage("Place Image as Layer…", "Shift+Ctrl+P"),
    ImportPsd("Import PSD/PSB…", planned = Roadmap.PSD),
    ImportRaw("Import Camera RAW…", planned = Roadmap.RAW),
    DocumentInfo("Document Info…", "Alt+Shift+Ctrl+I"),
    Export("Export PNG / JPEG…", "Alt+Shift+Ctrl+W"),
    Preferences("Preferences…", "Ctrl+K", planned = Roadmap.WORKSPACE),
    CloseDocument("Close", "Ctrl+W"),

    // Edit
    Undo("Undo", "Ctrl+Z"),
    Redo("Redo", "Shift+Ctrl+Z"),
    Cut("Cut", "Ctrl+X"),
    Copy("Copy", "Ctrl+C"),
    Paste("Paste", "Ctrl+V"),
    Clear("Clear", "Del"),
    Fill("Fill…", "Shift+F5"),
    Stroke("Stroke…", planned = Availability.Planned("M6", "Stroking selections is not built yet")),
    FreeTransform("Free Transform", "Ctrl+T"),
    FlipLayerHorizontal("Flip Layer Horizontal"),
    FlipLayerVertical("Flip Layer Vertical"),
    SwapColors("Swap Colors", "X"),
    ResetColors("Default Colors", "D"),
    ForegroundColor("Foreground Color…"),
    BackgroundColor("Background Color…"),

    // Image
    ImageSize("Image Size…", "Alt+Ctrl+I"),
    CanvasSize("Canvas Size…", "Alt+Ctrl+C"),
    RotateCanvasCw("Rotate 90° Clockwise"),
    RotateCanvasCcw("Rotate 90° Counter-clockwise"),
    RotateCanvas180("Rotate 180°"),
    FlipCanvasHorizontal("Flip Canvas Horizontal"),
    FlipCanvasVertical("Flip Canvas Vertical"),
    Crop("Crop"),
    Mode("Mode: RGB 8-bit only", planned = Roadmap.COLOR_MGMT),
    ConvertProfile("Assign / Convert Profile…", planned = Roadmap.COLOR_MGMT),

    // Layer
    NewLayer("New Layer", "Shift+Ctrl+N"),
    DuplicateLayer("Duplicate Layer", "Ctrl+J"),
    DeleteLayer("Delete Layer", "Del"),
    RenameLayer("Rename Layer…"),
    ToggleLayerVisibility("Show / Hide Layer", "Ctrl+,"),
    ToggleLayerLock("Lock / Unlock Layer", "Ctrl+/"),
    BringForward("Bring Forward", "Ctrl+]"),
    SendBackward("Send Backward", "Ctrl+["),
    BringToFront("Bring to Front", "Shift+Ctrl+]"),
    SendToBack("Send to Back", "Shift+Ctrl+["),
    ResetLayerTransform("Reset Layer Transform"),
    NewGroup("Group Layers", "Ctrl+G", planned = Roadmap.GROUPS),
    Ungroup("Ungroup Layers", "Shift+Ctrl+G", planned = Roadmap.GROUPS),
    AddMask("Add Mask: Reveal"),
    AddMaskHideAll("Add Mask: Hide"),
    DeleteMask("Delete Mask"),
    ApplyMask("Apply Mask"),
    ToggleMaskEnabled("Disable / Enable Mask"),
    InvertMask("Invert Mask", "Ctrl+I"),
    EditMask("Edit Mask / Pixels", "Ctrl+\\"),
    ViewMask("View Mask Alone"),
    MaskOverlay("Mask Overlay", "\\"),
    NewAdjustmentLayer("New Adjustment Layer…", planned = Roadmap.ADJUSTMENTS),
    LayerViaCopy("Layer via Copy", "Ctrl+J"),
    LayerViaCut("Layer via Cut", "Shift+Ctrl+J"),
    MergeDown("Merge Down", "Ctrl+E"),
    Flatten("Flatten Image…"),
    Rasterize("Rasterize", planned = Availability.Planned("M8", "Only raster layers exist today, so there is nothing to rasterize")),

    // Select
    SelectAll("All", "Ctrl+A"),
    Deselect("Deselect", "Ctrl+D"),
    InverseSelection("Inverse", "Shift+Ctrl+I"),
    Feather("Feather…", "Shift+F6", planned = Roadmap.SELECTION_REFINE),
    ExpandSelection("Expand…", planned = Roadmap.SELECTION_REFINE),
    ContractSelection("Contract…", planned = Roadmap.SELECTION_REFINE),
    ColorRange("Color Range…", planned = Roadmap.SMART_PICK),
    SelectSubject("Subject", planned = Roadmap.SMART_SELECT),

    // Filter
    GaussianBlur("Blur ▸ Gaussian Blur…", planned = Roadmap.FILTERS),
    Sharpen("Sharpen ▸ Unsharp Mask…", planned = Roadmap.FILTERS),
    AddNoise("Noise ▸ Add Noise…", planned = Roadmap.FILTERS),
    Distort("Distort…", planned = Roadmap.FILTERS),
    Stylize("Stylize…", planned = Roadmap.FILTERS),
    CameraRawFilter("Camera RAW Filter…", planned = Roadmap.RAW),
    Liquify("Liquify…", planned = Roadmap.LIQUIFY),
    LensCorrection("Lens Correction…", planned = Roadmap.RAW),

    // Adjustments (quick strip "Adjust" and Image ▸ Adjustments)
    AdjExposure("Exposure", planned = Roadmap.ADJUSTMENTS),
    AdjBrightnessContrast("Brightness / Contrast", planned = Roadmap.ADJUSTMENTS),
    AdjLevels("Levels", "Ctrl+L", planned = Roadmap.ADJUSTMENTS),
    AdjCurves("Curves", "Ctrl+M", planned = Roadmap.ADJUSTMENTS),
    AdjHueSaturation("Hue / Saturation", "Ctrl+U", planned = Roadmap.ADJUSTMENTS),
    AdjVibrance("Vibrance", planned = Roadmap.ADJUSTMENTS),
    AdjTemperature("Temperature / Tint", planned = Roadmap.ADJUSTMENTS),
    AdjShadowsHighlights("Shadows / Highlights", planned = Roadmap.ADJUSTMENTS),
    AdjColorBalance("Color Balance", "Ctrl+B", planned = Roadmap.ADJUSTMENTS),

    // FX (layer effects)
    FxDropShadow("Drop Shadow", planned = Roadmap.EFFECTS),
    FxStroke("Stroke", planned = Roadmap.EFFECTS),
    FxOuterGlow("Outer Glow", planned = Roadmap.EFFECTS),
    FxColorOverlay("Color Overlay", planned = Roadmap.EFFECTS),

    // View
    ZoomIn("Zoom In", "Ctrl+="),
    ZoomOut("Zoom Out", "Ctrl+-"),
    FitOnScreen("Fit on Screen", "Ctrl+0"),
    ActualPixels("Actual Pixels (100%)", "Ctrl+1"),
    RotateViewLeft("Rotate View −15°", "Shift+R"),
    RotateViewRight("Rotate View +15°", "R"),
    ResetViewRotation("Reset View Rotation", "Esc"),
    Rulers("Rulers", "Ctrl+R", planned = Roadmap.GUIDES),
    Guides("Guides", "Ctrl+;", planned = Roadmap.GUIDES),
    Grid("Grid", "Ctrl+'", planned = Roadmap.GUIDES),
    Snap("Snap", "Shift+Ctrl+;", planned = Roadmap.GUIDES),

    // Window
    WindowLayers("Layers", "F7"),
    WindowChannels("Channels"),
    WindowPaths("Paths"),
    WindowHistory("History"),
    WindowToolOptions("Tool Options"),
    WindowStatusBar("Status Bar"),
    WindowProperties("Properties", planned = Roadmap.PRO_PANELS),
    WindowHistogram("Histogram", planned = Roadmap.PRO_PANELS),
    WindowNavigator("Navigator", planned = Roadmap.PRO_PANELS),
    WindowBrushes("Brushes", planned = Roadmap.PRO_PANELS),
    WindowColor("Color", planned = Roadmap.PRO_PANELS),
    WindowSwatches("Swatches", planned = Roadmap.PRO_PANELS),
    WorkspacePresets("Workspace Presets", planned = Roadmap.WORKSPACE),
    ResetWorkspace("Reset Workspace"),

    // Help
    Documentation("Documentation", planned = Roadmap.DOCS),
    Shortcuts("Keyboard Shortcuts…", "Alt+Shift+Ctrl+K"),
    FeatureStatus("Feature Status…"),
    About("About pho.psd"),
    Diagnostics("Diagnostics…"),
}

/** One entry of a menu: a command, a section header or a separator. */
sealed interface MenuEntry {
    data class Item(val id: CommandId) : MenuEntry
    data class Header(val title: String) : MenuEntry
    data object Separator : MenuEntry
}

/** A top-level menu of the traditional menu bar. */
data class MenuSpec(val title: String, val entries: List<MenuEntry>)

object Menus {
    private fun items(vararg ids: CommandId) = ids.map { MenuEntry.Item(it) }
    private val sep = MenuEntry.Separator
    private fun h(t: String) = MenuEntry.Header(t)

    val bar: List<MenuSpec> = listOf(
        MenuSpec("File", items(CommandId.NewDocument, CommandId.OpenImage, CommandId.OpenFromPhotos, CommandId.OpenProject, CommandId.Recent) +
            sep + items(CommandId.Save, CommandId.SaveAs) +
            sep + items(CommandId.PlaceImage, CommandId.ImportPsd, CommandId.ImportRaw) +
            sep + items(CommandId.DocumentInfo, CommandId.Export) +
            sep + items(CommandId.Preferences, CommandId.CloseDocument)),
        MenuSpec("Edit", items(CommandId.Undo, CommandId.Redo) +
            sep + items(CommandId.Cut, CommandId.Copy, CommandId.Paste, CommandId.Clear) +
            sep + items(CommandId.Fill, CommandId.Stroke) +
            sep + h("Transform") + items(CommandId.FreeTransform, CommandId.FlipLayerHorizontal, CommandId.FlipLayerVertical) +
            sep + h("Colors") + items(CommandId.ForegroundColor, CommandId.BackgroundColor, CommandId.SwapColors, CommandId.ResetColors) +
            sep + items(CommandId.Preferences)),
        MenuSpec("Image", items(CommandId.ImageSize, CommandId.CanvasSize, CommandId.Crop) +
            sep + h("Image Rotation") + items(
                CommandId.RotateCanvasCw, CommandId.RotateCanvasCcw, CommandId.RotateCanvas180,
                CommandId.FlipCanvasHorizontal, CommandId.FlipCanvasVertical,
            ) +
            sep + h("Adjustments") + items(
                CommandId.AdjExposure, CommandId.AdjBrightnessContrast, CommandId.AdjLevels, CommandId.AdjCurves,
                CommandId.AdjHueSaturation, CommandId.AdjColorBalance,
            ) + sep + items(CommandId.Mode, CommandId.ConvertProfile)),
        MenuSpec("Layer", items(CommandId.NewLayer, CommandId.PlaceImage, CommandId.DuplicateLayer, CommandId.DeleteLayer, CommandId.RenameLayer) +
            sep + items(CommandId.ToggleLayerVisibility, CommandId.ToggleLayerLock) +
            sep + h("Arrange") + items(CommandId.BringToFront, CommandId.BringForward, CommandId.SendBackward, CommandId.SendToBack) +
            sep + items(CommandId.ResetLayerTransform, CommandId.FreeTransform, CommandId.LayerViaCopy, CommandId.LayerViaCut) +
            sep + h("Layer Mask") + items(
                CommandId.AddMask, CommandId.AddMaskHideAll, CommandId.EditMask, CommandId.ToggleMaskEnabled,
                CommandId.InvertMask, CommandId.ViewMask, CommandId.MaskOverlay, CommandId.ApplyMask, CommandId.DeleteMask,
            ) +
            sep + items(CommandId.NewGroup, CommandId.Ungroup, CommandId.NewAdjustmentLayer) +
            sep + items(CommandId.MergeDown, CommandId.Flatten, CommandId.Rasterize)),
        MenuSpec("Select", items(CommandId.SelectAll, CommandId.Deselect, CommandId.InverseSelection) +
            sep + h("Modify") + items(CommandId.Feather, CommandId.ExpandSelection, CommandId.ContractSelection) +
            sep + items(CommandId.ColorRange, CommandId.SelectSubject)),
        MenuSpec("Filter", items(CommandId.GaussianBlur, CommandId.Sharpen, CommandId.AddNoise, CommandId.Distort, CommandId.Stylize) +
            sep + items(CommandId.CameraRawFilter, CommandId.Liquify, CommandId.LensCorrection)),
        MenuSpec("View", items(CommandId.ZoomIn, CommandId.ZoomOut, CommandId.FitOnScreen, CommandId.ActualPixels) +
            sep + items(CommandId.RotateViewLeft, CommandId.RotateViewRight, CommandId.ResetViewRotation) +
            sep + items(CommandId.Rulers, CommandId.Guides, CommandId.Grid, CommandId.Snap)),
        MenuSpec("Window", listOf(h("Panels")) + items(
            CommandId.WindowLayers, CommandId.WindowChannels, CommandId.WindowPaths, CommandId.WindowHistory,
            CommandId.WindowToolOptions, CommandId.WindowStatusBar,
        ) + sep + items(
            CommandId.WindowProperties, CommandId.WindowHistogram, CommandId.WindowNavigator,
            CommandId.WindowBrushes, CommandId.WindowColor, CommandId.WindowSwatches,
        ) + sep + items(CommandId.WorkspacePresets, CommandId.ResetWorkspace)),
        MenuSpec("Help", items(CommandId.Documentation, CommandId.Shortcuts, CommandId.FeatureStatus) +
            sep + items(CommandId.Diagnostics, CommandId.About)),
    )
}

/** The quick category strip (Edit | Adjust | FX | Filters | Layer | Select). */
enum class QuickCategory(val label: String, val commands: List<CommandId>) {
    Edit("Edit", listOf(
        CommandId.Undo, CommandId.Redo, CommandId.FreeTransform, CommandId.Fill, CommandId.Clear,
        CommandId.Cut, CommandId.Copy, CommandId.Paste, CommandId.Crop,
    )),
    Adjust("Adjust", listOf(
        CommandId.AdjExposure, CommandId.AdjBrightnessContrast, CommandId.AdjLevels, CommandId.AdjCurves,
        CommandId.AdjHueSaturation, CommandId.AdjVibrance, CommandId.AdjTemperature, CommandId.AdjShadowsHighlights,
        CommandId.AdjColorBalance,
    )),
    Fx("FX", listOf(CommandId.FxDropShadow, CommandId.FxStroke, CommandId.FxOuterGlow, CommandId.FxColorOverlay)),
    Filters("Filters", listOf(
        CommandId.GaussianBlur, CommandId.Sharpen, CommandId.AddNoise, CommandId.CameraRawFilter,
        CommandId.Liquify, CommandId.LensCorrection,
    )),
    Layer("Layer", listOf(
        CommandId.NewLayer, CommandId.PlaceImage, CommandId.DuplicateLayer, CommandId.DeleteLayer,
        CommandId.BringForward, CommandId.SendBackward, CommandId.ResetLayerTransform, CommandId.AddMask,
        CommandId.LayerViaCopy, CommandId.MergeDown, CommandId.NewGroup,
    )),
    Select("Select", listOf(CommandId.SelectAll, CommandId.Deselect, CommandId.InverseSelection, CommandId.SelectSubject, CommandId.ColorRange)),
}
