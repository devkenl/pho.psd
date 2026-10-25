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
    val SELECTION_REFINE = Availability.Planned("M6", "Feathering and selection refinements arrive with mask-native refinement tools")
    val SMART_PICK = Availability.Planned("M6", "Colour-based selection (magic wand, quick selection) is not built yet")
    val MAGNETIC = Availability.Planned("M6", "Edge-snapping lasso is not built yet; use Lasso or Polygonal Lasso")
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
