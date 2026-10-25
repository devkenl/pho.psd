package app.phopsd.engine

import app.phopsd.engine.geometry.Affine
import app.phopsd.engine.geometry.LayerTransform
import app.phopsd.engine.geometry.PointD
import app.phopsd.engine.geometry.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    private val eps = 1e-9

    private fun assertPoint(expected: PointD, actual: PointD, tol: Double = 1e-7) {
        assertEquals("x", expected.x, actual.x, tol)
        assertEquals("y", expected.y, actual.y, tol)
    }

    @Test
    fun affineCompositionAppliesLeftToRight() {
        val t = Affine.translate(10.0, 0.0).then(Affine.scale(2.0))
        assertPoint(PointD(22.0, 0.0), t.map(PointD(1.0, 0.0)))
    }

    @Test
    fun affineInverseRoundTrips() {
        val t = Affine.translate(3.0, -7.0)
            .then(Affine.rotateDegrees(33.0))
            .then(Affine.scale(1.7, 0.4))
        val inv = t.invert()!!
        val p = PointD(123.4, -56.7)
        assertPoint(p, inv.map(t.map(p)))
        assertPoint(p, t.then(inv).map(p))
    }

    @Test
    fun singularAffineHasNoInverse() {
        assertNull(Affine.scale(0.0, 1.0).invert())
    }

    @Test
    fun rotationIsClockwiseOnYDownScreen() {
        assertPoint(PointD(0.0, 1.0), Affine.rotateDegrees(90.0).map(PointD(1.0, 0.0)))
    }

    @Test
    fun matrixValuesMatchAndroidLayout() {
        val v = Affine(a = 1.0, b = 2.0, c = 3.0, d = 4.0, tx = 5.0, ty = 6.0).toMatrixValues()
        assertTrue(v.contentEquals(floatArrayOf(1f, 3f, 5f, 2f, 4f, 6f, 0f, 0f, 1f)))
    }

    @Test
    fun viewportScreenDocumentRoundTripWithRotation() {
        val vp = Viewport(1600.0, 1000.0, 4000, 3000, zoom = 0.37, centerX = 1200.0, centerY = 900.0, rotationDegrees = 27.0)
        val doc = PointD(345.6, 2789.1)
        val s = vp.toScreen(doc.x, doc.y)
        assertPoint(doc, vp.toDocument(s.x, s.y))
        // The document centre point is shown at the centre of the view.
        assertPoint(PointD(800.0, 500.0), vp.toScreen(1200.0, 900.0))
    }

    @Test
    fun zoomAtKeepsFocusPointFixed() {
        val vp = Viewport(1200.0, 800.0, 3000, 2000, zoom = 0.25, rotationDegrees = -15.0)
        val focus = PointD(300.0, 650.0)
        val before = vp.toDocument(focus.x, focus.y)
        val z = vp.zoomAt(focus.x, focus.y, 3.0)
        assertEquals(0.75, z.zoom, eps)
        assertPoint(before, z.toDocument(focus.x, focus.y))
    }

    @Test
    fun zoomIsClamped() {
        val vp = Viewport(100.0, 100.0, 10, 10, zoom = 1.0)
        assertEquals(Viewport.MAX_ZOOM, vp.zoomAt(0.0, 0.0, 1e6).zoom, eps)
        assertEquals(Viewport.MIN_ZOOM, vp.zoomAt(0.0, 0.0, 1e-9).zoom, eps)
    }

    @Test
    fun panMovesContentWithFinger() {
        val vp = Viewport(1000.0, 1000.0, 2000, 2000, zoom = 0.5, rotationDegrees = 90.0)
        val p = PointD(500.0, 700.0)
        val before = vp.toScreen(p.x, p.y)
        val panned = vp.panByScreen(40.0, -25.0)
        val after = panned.toScreen(p.x, p.y)
        assertPoint(PointD(before.x + 40.0, before.y - 25.0), after)
    }

    @Test
    fun rotateAtKeepsFocusFixed() {
        val vp = Viewport(1000.0, 800.0, 2000, 1000, zoom = 0.4)
        val before = vp.toDocument(100.0, 120.0)
        val r = vp.rotateAt(100.0, 120.0, 45.0)
        assertEquals(45.0, r.rotationDegrees, eps)
        assertPoint(before, r.toDocument(100.0, 120.0))
    }

    @Test
    fun fitToViewFitsAndCentres() {
        val vp = Viewport(1000.0, 500.0, 4000, 1000).fitToView(marginPx = 0.0)
        assertEquals(0.25, vp.zoom, eps)
        assertPoint(PointD(500.0, 250.0), vp.toScreen(2000.0, 500.0))
        // Rotated 90°: 1000 tall doc side now spans the width.
        val rot = Viewport(1000.0, 500.0, 4000, 1000, rotationDegrees = 90.0).fitToView(marginPx = 0.0)
        assertEquals(0.125, rot.zoom, eps)
    }

    @Test
    fun visibleRectIsClippedToDocument() {
        val vp = Viewport(100.0, 100.0, 1000, 1000, zoom = 1.0, centerX = 20.0, centerY = 500.0)
        val r = vp.visibleDocumentRect()
        assertEquals(0.0, r.left, eps)
        assertEquals(70.0, r.right, eps)
        assertEquals(450.0, r.top, eps)
        assertEquals(550.0, r.bottom, eps)
    }

    @Test
    fun zoomStopsStepMonotonically() {
        assertEquals(1.5, Viewport.nextZoomStop(1.0), eps)
        assertEquals(0.6667, Viewport.previousZoomStop(1.0), eps)
        assertEquals(1.0, Viewport.nextZoomStop(0.9), eps)
    }

    @Test
    fun layerTransformMapsCornersAroundCentre() {
        val t = LayerTransform.atOffset(100.0, 50.0, 200, 100)
        val m = t.layerToDocument(200, 100)
        assertPoint(PointD(100.0, 50.0), m.map(PointD(0.0, 0.0)))
        assertPoint(PointD(300.0, 150.0), m.map(PointD(200.0, 100.0)))

        val rotated = t.copy(rotationDegrees = 90.0, scaleX = 2.0, scaleY = 2.0).layerToDocument(200, 100)
        // Centre stays put; layer top-left (-100,-50 from centre) -> scaled x2 -> rotated 90° cw.
        assertPoint(PointD(200.0, 100.0), rotated.map(PointD(100.0, 50.0)))
        assertPoint(PointD(200.0 + 100.0, 100.0 - 200.0), rotated.map(PointD(0.0, 0.0)))
    }

    @Test
    fun documentToLayerIsInverse() {
        val t = LayerTransform(512.3, 77.0, 0.33, 0.5, -12.0)
        val p = PointD(10.0, 20.0)
        assertPoint(p, t.documentToLayer(640, 480).map(t.layerToDocument(640, 480).map(p)))
    }
}
