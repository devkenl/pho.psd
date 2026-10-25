package app.phopsd.editor

/**
 * Whether a command/tool can run right now. Unavailable items stay visible (so the full
 * workspace is discoverable) but explain *why* instead of pretending to work.
 */
sealed interface Availability {
    data object Enabled : Availability

    /** Temporarily unavailable in the current state (e.g. nothing to undo). */
    data class Blocked(val reason: String) : Availability

    /** Not implemented yet. [milestone] is the roadmap milestone that delivers it. */
    data class Planned(val milestone: String, val reason: String) : Availability

    val isEnabled: Boolean get() = this is Enabled

    val explanation: String?
        get() = when (this) {
            Enabled -> null
            is Blocked -> reason
            is Planned -> "$reason ($milestone)"
        }
}

/** Shared "not built yet" reasons so wording stays consistent and greppable. */
object Roadmap {
    val SAVE = Availability.Planned("M3", "Native .phopsd projects arrive with save/reopen")
    val EXPORT = Availability.Planned("M3", "PNG/JPEG export arrives with project persistence")
    val PAINT = Availability.Planned("M4", "The brush engine is not built yet")
    val SELECTION = Availability.Planned("M4", "The selection engine is not built yet")
    val CROP_TRANSFORM = Availability.Planned("M4", "Crop and free-transform handles are not built yet")
    val MASKS = Availability.Planned("M4", "Layer masks and mask painting are not built yet")
    val PIXEL_EDIT = Availability.Planned("M4", "Pixel editing (cut, fill, merge) is not built yet")
    val CANVAS_OPS = Availability.Planned("M4", "Image/canvas size and rotation are not built yet")
    val ADJUSTMENTS = Availability.Planned("M5", "Non-destructive adjustment layers are not built yet")
    val GROUPS = Availability.Planned("M6", "Layer groups are not built yet")
    val RETOUCH = Availability.Planned("M6", "Clone and healing tools are not built yet")
    val GRADIENT = Availability.Planned("M6", "Gradient and fill tools are not built yet")
    val FILTERS = Availability.Planned("M6", "Filters arrive after the adjustment pipeline")
    val EFFECTS = Availability.Planned("M6+", "Layer effects are not built yet")
    val WORKSPACE = Availability.Planned("M7", "Workspace customization and preferences are not built yet")
    val GUIDES = Availability.Planned("M7", "Rulers, guides, grid and snapping are not built yet")
    val PRO_PANELS = Availability.Planned("M7", "This panel is not built yet")
    val TEXT = Availability.Planned("M8", "Text layers are not built yet")
    val PATHS = Availability.Planned("M8", "Vector paths are not built yet")
    val COLOR_MGMT = Availability.Planned("M8", "ICC profile conversion and 16-bit modes are not built yet")
    val SMART_SELECT = Availability.Planned("M8", "On-device subject selection is not built yet")
    val RAW = Availability.Planned("M8", "Camera RAW processing is not built yet")
    val LIQUIFY = Availability.Planned("M8", "Liquify and warp are not built yet")
    val PSD = Availability.Planned("M8", "Layered PSD/PSB import is not built yet")
    val DOCS = Availability.Planned("later", "Offline documentation has not been written yet")
}
