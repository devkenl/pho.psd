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
            ctrl && e.key == Key.J -> CommandId.DuplicateLayer
            ctrl && e.key == Key.T -> CommandId.FreeTransform
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
            !ctrl && (e.key == Key.Delete || e.key == Key.Backspace) -> CommandId.DeleteLayer
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

        val tool: Tool? = when (e.key) {
            Key.V -> Tool.Move
            Key.H -> Tool.Hand
            Key.Z -> Tool.Zoom
            Key.B -> Tool.Brush
            Key.E -> Tool.Eraser
            Key.M -> Tool.RectMarquee
            Key.L -> Tool.Lasso
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
}
