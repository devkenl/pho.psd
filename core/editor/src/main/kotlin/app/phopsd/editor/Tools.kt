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
    RectMarquee("Rectangular Marquee", "M", Roadmap.SELECTION),
    EllipticalMarquee("Elliptical Marquee", "M", Roadmap.SELECTION),
    Lasso("Lasso", "L", Roadmap.SELECTION),
    PolygonalLasso("Polygonal Lasso", "L", Roadmap.SELECTION),
    MagneticLasso("Magnetic Lasso", "L", Roadmap.SELECTION),
    ObjectSelection("Object Selection", "W", Roadmap.SMART_SELECT),
    QuickSelection("Quick Selection", "W", Roadmap.SELECTION),
    MagicWand("Magic Wand", "W", Roadmap.SELECTION),
    Move("Move", "V", Availability.Enabled),
    Brush("Brush", "B", Roadmap.PAINT),
    Healing("Healing Brush", "J", Roadmap.RETOUCH),
    CloneStamp("Clone Stamp", "S", Roadmap.RETOUCH),
    Eraser("Eraser", "E", Roadmap.PAINT),
    Crop("Crop", "C", Roadmap.CROP_TRANSFORM),
    Transform(
        "Free Transform", "Ctrl+T",
        Availability.Planned("M4", "On-canvas transform handles are not built yet; use Move tool options for numeric scale/rotation"),
    ),
    Gradient("Gradient", "G", Roadmap.GRADIENT),
    PaintBucket("Paint Bucket", "G", Roadmap.GRADIENT),
    Text("Horizontal Type", "T", Roadmap.TEXT),
    Mask("Mask", null, Roadmap.MASKS),
    Hand("Hand", "H", Availability.Enabled),
    Zoom("Zoom", "Z", Availability.Enabled),
    Pen("Pen", "P", Roadmap.PATHS),
    Eyedropper("Eyedropper", "I", Availability.Planned("M4", "Colour sampling arrives with the brush engine")),
}

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
