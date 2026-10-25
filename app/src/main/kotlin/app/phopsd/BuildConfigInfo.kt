package app.phopsd

/**
 * Version info shown in About/Diagnostics. Kept as a constant (rather than enabling
 * BuildConfig generation) and must match `versionName` in app/build.gradle.kts.
 */
object BuildConfigInfo {
    const val VERSION_NAME = "0.3.0-dev"
    const val MILESTONE = "M0–M3 (workspace, image import, raster layers, projects, export)"
}
