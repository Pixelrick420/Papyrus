package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class AffineTest {

    @Test
    fun `identity maps a point unchanged`() {
        assertPoint(3.0, 4.0, Affine.IDENTITY.map(3.0, 4.0))
    }

    @Test
    fun `translate moves by its offset`() {
        assertPoint(11.0, -3.0, Affine.translate(10.0, -5.0).map(1.0, 2.0))
    }

    @Test
    fun `scale multiplies about the origin`() {
        assertPoint(8.0, 15.0, Affine.scale(2.0, 3.0).map(4.0, 5.0))
    }

    @Test
    fun `then applies this transform before the next`() {
        assertPoint(20.0, 0.0, Affine.translate(10.0, 0.0).then(Affine.scale(2.0, 2.0)).map(0.0, 0.0))
        assertPoint(10.0, 0.0, Affine.scale(2.0, 2.0).then(Affine.translate(10.0, 0.0)).map(0.0, 0.0))
    }

    @Test
    fun `rotation turns clockwise in a y-down space`() {
        assertPoint(0.0, 1.0, Affine.rotate(PI / 2).map(1.0, 0.0))
        assertPoint(-1.0, 0.0, Affine.rotate(PI).map(1.0, 0.0))
    }

    @Test
    fun `flip mirrors about the centre`() {
        assertPoint(9.0, 0.0, Affine.about(ScenePoint(5.0, 5.0), Affine.flip(true, false)).map(1.0, 0.0))
        assertPoint(1.0, 10.0, Affine.about(ScenePoint(5.0, 5.0), Affine.flip(false, true)).map(1.0, 0.0))
    }

    @Test
    fun `a group maps its child space onto the frame`() {
        val group = Affine.forShape(SceneRect(100.0, 200.0, 400.0, 300.0), SceneRect(0.0, 0.0, 200.0, 100.0))
        assertPoint(100.0, 200.0, group.map(0.0, 0.0))
        assertPoint(500.0, 500.0, group.map(200.0, 100.0))
    }

    @Test
    fun `a leaf shape places local coordinates at its offset`() {
        val shape = Affine.forShape(SceneRect(100.0, 200.0, 400.0, 300.0))
        assertPoint(100.0, 200.0, shape.map(0.0, 0.0))
        assertPoint(500.0, 500.0, shape.map(400.0, 300.0))
    }

    @Test
    fun `rotation turns about the frame centre`() {
        val shape = Affine.forShape(SceneRect(0.0, 0.0, 100.0, 100.0), rotation = QUARTER_TURN)
        assertPoint(100.0, 0.0, shape.map(0.0, 0.0))
        assertPoint(0.0, 100.0, shape.map(100.0, 100.0))
    }

    @Test
    fun `flip happens before rotation`() {
        val shape = Affine.forShape(SceneRect(0.0, 0.0, 100.0, 100.0), rotation = QUARTER_TURN, flipH = true)
        assertPoint(100.0, 100.0, shape.map(0.0, 0.0))
    }

    @Test
    fun `nested groups compose through every level`() {
        val inner = Affine.forShape(SceneRect(0.0, 0.0, 100.0, 100.0), SceneRect(0.0, 0.0, 50.0, 50.0))
        val outer = Affine.forShape(SceneRect(0.0, 0.0, 200.0, 200.0), SceneRect(0.0, 0.0, 100.0, 100.0))
        val composed = inner.then(outer)
        assertPoint(0.0, 0.0, composed.map(0.0, 0.0))
        assertPoint(100.0, 100.0, composed.map(25.0, 25.0))
        assertPoint(200.0, 200.0, composed.map(50.0, 50.0))
    }

    @Test
    fun `a zero child extent does not divide by zero`() {
        val group = Affine.forShape(SceneRect(10.0, 20.0, 100.0, 100.0), SceneRect(0.0, 0.0, 0.0, 0.0))
        val point = group.map(5.0, 5.0)
        assertTrue(point.x.isFinite() && point.y.isFinite())
        assertPoint(15.0, 25.0, point)
    }

    @Test
    fun `a negative child extent keeps its direction`() {
        val group = Affine.forShape(SceneRect(0.0, 0.0, 100.0, 100.0), SceneRect(0.0, 0.0, -100.0, -100.0))
        assertPoint(-100.0, -100.0, group.map(100.0, 100.0))
    }

    @Test
    fun `absurd extents are clamped`() {
        val group = Affine.forShape(
            SceneRect(0.0, 0.0, 1e12, 100.0),
            SceneRect(0.0, 0.0, 1.0, 1.0),
        )
        val point = group.map(1.0, 0.0)
        assertTrue(point.x.isFinite() && point.y.isFinite())
        assertEquals(Affine.MAX_EDGE_EMU, point.x, 1e-3)
    }

    private fun assertPoint(expectedX: Double, expectedY: Double, point: ScenePoint) {
        assertEquals(expectedX, point.x, 1e-6)
        assertEquals(expectedY, point.y, 1e-6)
        assertTrue(abs(point.x) < 1e12 && abs(point.y) < 1e12)
    }

    private companion object {
        const val QUARTER_TURN = 5_400_000.0
    }
}
