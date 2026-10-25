package app.phopsd.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.phopsd.editor.CommandId
import app.phopsd.editor.EditorController
import app.phopsd.editor.Screen
import app.phopsd.editor.Tool

/**
 * Hardware keyboard shortcuts. Every shortcut dispatches the same [CommandId]s as the
 * menus, so planned commands explain themselves instead of silently doing nothing.
 */
object KeyShortcuts {

    fun handle(e: KeyEvent, c: EditorController): Boolean {
        val s = c.state.value
        if (s.dialog != null) return false
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val shift = e.isShiftPressed

        if (e.key == Key.Spacebar && s.screen == Screen.Editor) {
            c.setSpacePanning(e.type == KeyEventType.KeyDown)
            return true
        }
        if (e.type != KeyEventType.KeyDown) return false
        val alt = e.isAltPressed
        val editor = s.screen == Screen.Editor
        val hasSel = s.document?.selection != null

        // Enter / Esc finish or cancel the gesture-based tools first.
        if (editor && !ctrl && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
            when {
                s.tool == Tool.Crop && s.crop != null -> { c.tools.commitCrop(); return true }
                s.selectionDraft != null -> { c.tools.finishPolygon(); return true }
            }
        }
        if (editor && e.key == Key.Escape) {
            when {
                s.selectionDraft != null -> { c.tools.cancelSelectionDraft(); return true }
                s.tool == Tool.Crop && s.crop != null -> { c.tools.cancelCrop(); return true }
                s.maskView != null -> { c.tools.setMaskView(null); return true }
            }
        }

        val command: CommandId? = when {
            ctrl && shift && e.key == Key.Z -> CommandId.Redo
            ctrl && e.key == Key.Z -> CommandId.Undo
            ctrl && e.key == Key.Y -> CommandId.Redo
            ctrl && shift && e.key == Key.N -> CommandId.NewLayer
            ctrl && e.key == Key.N -> CommandId.NewDocument
            ctrl && shift && e.key == Key.O -> CommandId.OpenProject
            ctrl && e.key == Key.O -> CommandId.OpenImage
            ctrl && shift && e.isAltPressed && e.key == Key.W -> CommandId.Export
            ctrl && shift && e.key == Key.P -> CommandId.PlaceImage
            ctrl && shift && e.key == Key.S -> CommandId.SaveAs
            ctrl && e.key == Key.S -> CommandId.Save
            ctrl && e.key == Key.W -> CommandId.CloseDocument
            ctrl && shift && e.key == Key.J -> CommandId.LayerViaCut
            ctrl && e.key == Key.J -> if (hasSel) CommandId.LayerViaCopy else CommandId.DuplicateLayer
            ctrl && e.key == Key.T -> CommandId.FreeTransform
            ctrl && e.key == Key.X -> CommandId.Cut
            ctrl && e.key == Key.C && alt -> CommandId.CanvasSize
            ctrl && e.key == Key.C -> CommandId.Copy
            ctrl && e.key == Key.V -> CommandId.Paste
            ctrl && e.key == Key.E -> CommandId.MergeDown
            ctrl && alt && e.key == Key.I -> CommandId.ImageSize
            ctrl && shift && e.key == Key.I -> CommandId.InverseSelection
            ctrl && e.key == Key.I -> CommandId.InvertMask
            ctrl && e.key == Key.Backslash -> CommandId.EditMask
            !ctrl && e.key == Key.Backslash && editor -> CommandId.MaskOverlay
            shift && e.key == Key.F5 -> CommandId.Fill
            ctrl && shift && e.key == Key.RightBracket -> CommandId.BringToFront
            ctrl && e.key == Key.RightBracket -> CommandId.BringForward
            ctrl && shift && e.key == Key.LeftBracket -> CommandId.SendToBack
            ctrl && e.key == Key.LeftBracket -> CommandId.SendBackward
            ctrl && (e.key == Key.Equals || e.key == Key.Plus || e.key == Key.NumPadAdd) -> CommandId.ZoomIn
            ctrl && (e.key == Key.Minus || e.key == Key.NumPadSubtract) -> CommandId.ZoomOut
            ctrl && e.key == Key.Zero -> CommandId.FitOnScreen
            ctrl && e.key == Key.One -> CommandId.ActualPixels
            ctrl && e.key == Key.A -> CommandId.SelectAll
            ctrl && e.key == Key.D -> CommandId.Deselect
            !ctrl && (e.key == Key.Delete || e.key == Key.Backspace) -> if (hasSel) CommandId.Clear else CommandId.DeleteLayer
            e.key == Key.F7 -> CommandId.WindowLayers
            !ctrl && shift && e.key == Key.R -> CommandId.RotateViewLeft
            !ctrl && e.key == Key.R -> CommandId.RotateViewRight
            e.key == Key.Escape && (s.viewport?.rotationDegrees ?: 0.0) != 0.0 -> CommandId.ResetViewRotation
            else -> null
        }
        if (command != null) {
            if (s.screen == Screen.Home && command !in HOME_COMMANDS) return false
            c.dispatch(command)
            return true
        }
        if (s.screen != Screen.Editor || ctrl) return false

        // Brush keys: [ ] size, Shift+[ ] hardness, 1–0 opacity, X swap, D default colours.
        when (e.key) {
            Key.LeftBracket -> { if (shift) c.tools.nudgeHardness(-0.25) else c.tools.scaleBrushSize(1 / 1.25); return true }
            Key.RightBracket -> { if (shift) c.tools.nudgeHardness(0.25) else c.tools.scaleBrushSize(1.25); return true }
            Key.X -> { c.dispatch(CommandId.SwapColors); return true }
            Key.D -> { c.dispatch(CommandId.ResetColors); return true }
            else -> {}
        }
        val digit = DIGITS.indexOf(e.key)
        if (digit >= 0 && (s.tool == Tool.Brush || s.tool == Tool.Eraser || s.tool == Tool.Mask)) {
            c.tools.setOpacityPercent(if (digit == 0) 100 else digit * 10)
            return true
        }

        val tool: Tool? = when (e.key) {
            Key.V -> Tool.Move
            Key.H -> Tool.Hand
            Key.Z -> Tool.Zoom
            Key.B -> Tool.Brush
            Key.E -> Tool.Eraser
            Key.M -> if (shift && s.tool == Tool.RectMarquee) Tool.EllipticalMarquee else if (shift) Tool.RectMarquee else if (s.tool == Tool.EllipticalMarquee) Tool.EllipticalMarquee else Tool.RectMarquee
            Key.L -> if (shift && s.tool == Tool.Lasso) Tool.PolygonalLasso else if (s.tool == Tool.PolygonalLasso && !shift) Tool.PolygonalLasso else Tool.Lasso
            Key.W -> Tool.QuickSelection
            Key.C -> Tool.Crop
            Key.T -> Tool.Text
            Key.G -> Tool.Gradient
            Key.J -> Tool.Healing
            Key.S -> Tool.CloneStamp
            Key.I -> Tool.Eyedropper
            Key.P -> Tool.Pen
            else -> null
        }
        if (tool != null) {
            c.selectTool(tool)
            return true
        }

        if (s.tool == Tool.Move) {
            val step = if (shift) 10.0 else 1.0
            when (e.key) {
                Key.DirectionLeft -> { c.nudgeActiveLayer(-step, 0.0); return true }
                Key.DirectionRight -> { c.nudgeActiveLayer(step, 0.0); return true }
                Key.DirectionUp -> { c.nudgeActiveLayer(0.0, -step); return true }
                Key.DirectionDown -> { c.nudgeActiveLayer(0.0, step); return true }
                else -> {}
            }
        }
        return false
    }

    private val HOME_COMMANDS = setOf(CommandId.NewDocument, CommandId.OpenImage, CommandId.OpenProject)
    private val DIGITS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
}
