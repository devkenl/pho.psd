package app.phopsd.engine

import app.phopsd.engine.geometry.AngleSnap
import app.phopsd.engine.geometry.LayerPinch
import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.geometry.PointD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GesturesTest {
    private val eps = 1e-9

    @Test
    fun snapsOnlyWithinThreshold() {
        val s = AngleSnap(stepDegrees = 45.0, thresholdDegrees = 5.0)
        assertEquals(45.0, s.apply(41.0), eps)
        assertEquals(45.0, s.apply(49.9), eps)
        assertEquals(38.0, s.apply(38.0), eps)
        assertEquals(0.0, s.apply(-3.0), eps)
        assertEquals(-90.0, s.apply(-93.0), eps)
        assertEquals(360.0, s.apply(362.0), eps) // continuous, never wraps mid-gesture
        assertNull(s.target(22.5))
    }

    @Test
    fun disabledSnapIsIdentity() {
        assertEquals(44.0, AngleSnap.OFF.apply(44.0), eps)
        assertNull(AngleSnap.OFF.target(45.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun thresholdMustLeaveRoomBetweenSteps() {
        AngleSnap(stepDegrees = 15.0, thresholdDegrees = 10.0)
    }

    @Test
    fun thresholdForStep() {
        assertEquals(5.0, AngleSnap.thresholdFor(90.0), eps)
        assertEquals(5.0, AngleSnap.thresholdFor(15.0), eps)
        assertEquals(1.0, AngleSnap.thresholdFor(3.0), eps)
    }

    @Test
    fun pinchKeepsAnchorUnderFingers() {
        val start = LayerTransform(100.0, 50.0, 1.0, 1.0, 0.0)
        val anchor = PointD(120.0, 50.0) // 20 px right of centre
        val t = LayerPinch.apply(start, anchor, currentCentroid = PointD(200.0, 100.0), scaleFactor = 2.0, rotationDelta = 90.0)
        assertEquals(2.0, t.scaleX, eps)
        assertEquals(90.0, t.rotationDegrees, eps)
        // Centre was 20 px left of the anchor; scaled ×2 → 40 px, rotated 90° cw → 40 px up.
        assertEquals(200.0, t.centerX, 1e-7)
        assertEquals(60.0, t.centerY, 1e-7)
        // The layer pixel that was under the anchor is under the current centroid.
        val layerPt = start.documentToLayer(200, 100).map(anchor)
        val now = t.layerToDocument(200, 100).map(layerPt)
        assertEquals(200.0, now.x, 1e-7)
        assertEquals(100.0, now.y, 1e-7)
    }

    @Test
    fun pinchScaleIsClampedAndRotationNormalized() {
        val start = LayerTransform(0.0, 0.0, 0.5, 0.5, 170.0)
        val t = LayerPinch.apply(start, PointD(0.0, 0.0), PointD(0.0, 0.0), scaleFactor = 1e6, rotationDelta = 20.0)
        assertEquals(LayerPinch.MAX_SCALE, t.scaleX, eps)
        assertEquals(-170.0, t.rotationDegrees, eps)
        val tiny = LayerPinch.apply(start, PointD(0.0, 0.0), PointD(0.0, 0.0), scaleFactor = 1e-9, rotationDelta = 0.0)
        assertEquals(LayerPinch.MIN_SCALE, tiny.scaleX, eps)
    }
}
