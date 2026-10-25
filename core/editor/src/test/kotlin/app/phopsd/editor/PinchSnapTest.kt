package app.phopsd.editor

import app.phopsd.engine.model.NewDocumentSpec
import app.phopsd.engine.model.RasterLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchSnapTest {
    private val c = EditorController(clock = { 0L })
    private val eps = 1e-9

    private fun setup() {
        assertTrue(c.createDocument(NewDocumentSpec("T", 1000, 800)))
        c.setViewSize(1048.0, 848.0) // fit at zoom 1.0, doc offset (24, 24)
    }

    private fun rotateView(totalDegrees: Double, steps: Int = 10) {
        c.beginPinch(500.0, 400.0)
        repeat(steps) { c.updatePinch(500.0, 400.0, 0.0, 0.0, 1.0, totalDegrees / steps) }
    }

    @Test
    fun smallTwistNeverRotatesTheView() {
        setup()
        rotateView(8.0)
        assertEquals(0.0, c.state.value.viewport!!.rotationDegrees, eps)
        assertNull(c.state.value.rotationHud)
        c.endPinch()
    }

    @Test
    fun viewRotationSnapsTo45AndCanBreakFree() {
        setup()
        rotateView(42.0) // within 5° of 45 → snaps
        assertEquals(45.0, c.state.value.viewport!!.rotationDegrees, eps)
        val hud = c.state.value.rotationHud!!
        assertTrue(hud.snapped)
        assertEquals(TwoFingerTarget.View, hud.target)
        // Twisting further than the threshold leaves the snap (raw angle 42 + 10 = 52).
        c.updatePinch(500.0, 400.0, 0.0, 0.0, 1.0, 10.0)
        assertEquals(52.0, c.state.value.viewport!!.rotationDegrees, 1e-6)
        assertFalse(c.state.value.rotationHud!!.snapped)
        c.endPinch()
        assertNull(c.state.value.rotationHud)
    }

    @Test
    fun snapStepIsConfigurableAndCanBeDisabled() {
        setup()
        c.setRotationSnapStep(90.0)
        rotateView(42.0)
        assertEquals(42.0, c.state.value.viewport!!.rotationDegrees, 1e-6)
        c.endPinch()
        c.dispatch(CommandId.ResetViewRotation)
        c.setRotationSnapStep(null)
        rotateView(44.0)
        assertEquals(44.0, c.state.value.viewport!!.rotationDegrees, 1e-6)
        c.endPinch()
        c.setRotationSnapStep(15.0)
        assertTrue(c.state.value.rotationSnap.enabled)
        assertEquals(15.0, c.state.value.rotationSnap.stepDegrees, eps)
    }

    @Test
    fun snapIsAbsoluteEvenFromAnUnsnappedStart() {
        setup()
        c.setRotationSnapStep(null)
        rotateView(20.0)
        c.endPinch()
        c.setRotationSnapStep(45.0)
        rotateView(23.0) // 20 + 23 = 43 → snaps to 45 absolute
        assertEquals(45.0, c.state.value.viewport!!.rotationDegrees, eps)
        c.endPinch()
    }

    @Test
    fun pinchZoomKeepsCentroidFixed() {
        setup()
        val before = c.state.value.viewport!!.toDocument(300.0, 200.0)
        c.beginPinch(300.0, 200.0)
        c.updatePinch(300.0, 200.0, 0.0, 0.0, 2.0, 0.0)
        val after = c.state.value.viewport!!.toDocument(300.0, 200.0)
        assertEquals(2.0, c.state.value.viewport!!.zoom, eps)
        assertEquals(before.x, after.x, 1e-6)
        assertEquals(before.y, after.y, 1e-6)
        c.endPinch()
    }

    @Test
    fun moveToolCanTransformTheLayerWithSnapAsOneUndoStep() {
        setup()
        c.dispatch(CommandId.NewLayer)
        c.setTwoFingerTarget(TwoFingerTarget.Layer)
        val id = c.state.value.document!!.activeLayerId!!
        val steps = c.state.value.history!!.entries.size
        assertEquals(TwoFingerTarget.Layer, c.beginPinch(524.0, 424.0)) // doc (500, 400) = layer centre
        repeat(10) { c.updatePinch(524.0, 424.0, 0.0, 0.0, 1.05, 4.3) } // raw 43° → snaps to 45
        val live = c.state.value.document!!.layer(id) as RasterLayer
        assertEquals(45.0, live.transform.rotationDegrees, eps)
        assertEquals(Math.pow(1.05, 10.0), live.transform.scaleX, 1e-9)
        assertEquals(500.0, live.transform.centerX, 1e-6) // pivot under the fingers
        assertEquals(TwoFingerTarget.Layer, c.state.value.rotationHud!!.target)
        assertEquals(0.0, c.state.value.viewport!!.rotationDegrees, eps) // view untouched
        c.endPinch()
        assertNull(c.state.value.live)
        assertEquals(steps + 1, c.state.value.history!!.entries.size)
        c.dispatch(CommandId.Undo)
        assertTrue((c.state.value.document!!.layer(id) as RasterLayer).transform.isIdentityScaleAndRotation)
    }

    @Test
    fun layerPinchFollowsFingersWhilePanning() {
        setup()
        c.dispatch(CommandId.NewLayer)
        c.setTwoFingerTarget(TwoFingerTarget.Layer)
        c.beginPinch(524.0, 424.0)
        c.updatePinch(624.0, 474.0, 100.0, 50.0, 1.0, 0.0)
        val t = (c.state.value.document!!.activeLayer as RasterLayer).transform
        assertEquals(600.0, t.centerX, 1e-6)
        assertEquals(450.0, t.centerY, 1e-6)
        c.cancelPinch()
        assertNull(c.state.value.live)
    }

    @Test
    fun lockedLayerFallsBackToViewPinch() {
        setup()
        c.dispatch(CommandId.NewLayer)
        c.dispatch(CommandId.ToggleLayerLock)
        c.setTwoFingerTarget(TwoFingerTarget.Layer)
        assertEquals(TwoFingerTarget.View, c.beginPinch(500.0, 400.0))
        assertNotNull(c.state.value.notice)
        c.endPinch()
    }

    @Test
    fun otherToolsAlwaysPinchTheView() {
        setup()
        c.setTwoFingerTarget(TwoFingerTarget.Layer)
        c.selectTool(Tool.Hand)
        assertEquals(TwoFingerTarget.View, c.beginPinch(500.0, 400.0))
        c.endPinch()
    }
}
