package app.phopsd.editor

/**
 * Tools of the left rail. A [ToolGroup] is one rail button; its [ToolGroup.tools] are the
 * variants revealed by long-press / secondary click. Icons are mapped in the UI layer.
 */
enum class Tool(
    val label: String,
    val shortcut: String?,
    val availability: Availability,
) {
    RectMarquee("Rectangular Marquee", "M", Availability.Enabled),
    EllipticalMarquee("Elliptical Marquee", "M", Availability.Enabled),
    Lasso("Lasso", "L", Availability.Enabled),
    PolygonalLasso("Polygonal Lasso", "L", Availability.Enabled),
    MagneticLasso("Magnetic Lasso", "L", Roadmap.MAGNETIC),
    ObjectSelection("Object Selection", "W", Roadmap.SMART_SELECT),
    QuickSelection("Quick Selection", "W", Roadmap.SMART_PICK),
    MagicWand("Magic Wand", "W", Roadmap.SMART_PICK),
    Move("Move", "V", Availability.Enabled),
    Brush("Brush", "B", Availability.Enabled),
    Healing("Healing Brush", "J", Roadmap.RETOUCH),
    CloneStamp("Clone Stamp", "S", Roadmap.RETOUCH),
    Eraser("Eraser", "E", Availability.Enabled),
    Crop("Crop", "C", Availability.Enabled),
    Transform("Free Transform", "Ctrl+T", Availability.Enabled),
    Gradient("Gradient", "G", Roadmap.GRADIENT),
    PaintBucket("Paint Bucket", "G", Roadmap.GRADIENT),
    Text("Horizontal Type", "T", Roadmap.TEXT),
    Mask("Mask Brush", null, Availability.Enabled),
    Hand("Hand", "H", Availability.Enabled),
    Zoom("Zoom", "Z", Availability.Enabled),
    Pen("Pen", "P", Roadmap.PATHS),
    Eyedropper("Eyedropper", "I", Availability.Enabled),
}

/** Tools that put pixels (or mask values) down with the brush engine. */
val Tool.isBrushLike: Boolean get() = this == Tool.Brush || this == Tool.Eraser || this == Tool.Mask

/** Tools that draw a selection. */
val Tool.isSelectionTool: Boolean
    get() = this == Tool.RectMarquee || this == Tool.EllipticalMarquee || this == Tool.Lasso || this == Tool.PolygonalLasso

enum class ToolGroup(val label: String, val tools: List<Tool>) {
    Select("Select", listOf(
        Tool.RectMarquee, Tool.EllipticalMarquee, Tool.Lasso, Tool.PolygonalLasso,
        Tool.MagneticLasso, Tool.ObjectSelection, Tool.QuickSelection, Tool.MagicWand,
    )),
    Move("Move", listOf(Tool.Move)),
    Brush("Brush", listOf(Tool.Brush)),
    Healing("Healing", listOf(Tool.Healing)),
    Clone("Clone", listOf(Tool.CloneStamp)),
    Erase("Erase", listOf(Tool.Eraser)),
    Crop("Crop", listOf(Tool.Crop)),
    Transform("Transform", listOf(Tool.Transform)),
    Gradient("Gradient", listOf(Tool.Gradient, Tool.PaintBucket)),
    Text("Text", listOf(Tool.Text)),
    Adjust("Adjust", emptyList()),
    Mask("Mask", listOf(Tool.Mask)),
    Hand("Hand", listOf(Tool.Hand)),
    Zoom("Zoom", listOf(Tool.Zoom)),
    More("More", listOf(Tool.Pen, Tool.Eyedropper));

    val primary: Tool? get() = tools.firstOrNull()

    companion object {
        fun of(tool: Tool): ToolGroup = entries.first { tool in it.tools }
    }
}
