package com.papyrus.app.scanner

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

/** On-device scan, no ML Kit: detection warps the page, ScanFilter binarisation is applied at export time in PdfExporter. */
object DocumentProcessor {

    private const val DETECT_MAX_SIDE = 640.0
    private const val MIN_AREA_RATIO = 0.2

    private val openCvReady: Boolean by lazy { OpenCVLoader.initLocal() }

    /** Releases native Mats deterministically instead of waiting for the GC finalizer. */
    private class MatScope : AutoCloseable {
        private val mats = mutableListOf<Mat>()
        fun <T : Mat> T.track(): T = also { mats += it }
        override fun close() = mats.forEach { it.release() }
    }

    /** Returns [src] itself, not null, when no page outline is found. */
    fun detectAndWarp(src: Bitmap): Bitmap {
        check(openCvReady) { "OpenCV native library failed to load" }
        MatScope().use { scope ->
            with(scope) {
                val rgba = Mat().track()
                Utils.bitmapToMat(src, rgba)
                val corners = findCorners(rgba) ?: return src
                return warp(rgba, corners)
            }
        }
    }

    fun applyFilter(src: Bitmap, filter: ScanFilter): Bitmap {
        if (filter == ScanFilter.ORIGINAL) return src
        check(openCvReady) { "OpenCV native library failed to load" }
        MatScope().use { scope ->
            with(scope) {
                val rgba = Mat().track()
                Utils.bitmapToMat(src, rgba)
                val gray = Mat().track()
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                val result = if (filter == ScanFilter.BLACK_AND_WHITE) {
                    Mat().track().also {
                        Imgproc.adaptiveThreshold(
                            gray, it, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                            Imgproc.THRESH_BINARY, 31, 15.0,
                        )
                    }
                } else {
                    gray
                }
                val out = createBitmap(src.width, src.height)
                Utils.matToBitmap(result, out)
                return out
            }
        }
    }

    private fun MatScope.findCorners(rgba: Mat): Array<Point>? {
        val scale = DETECT_MAX_SIDE / max(rgba.cols(), rgba.rows())
        val small = Mat().track()
        Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val gray = Mat().track()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edges = Mat().track()
        Imgproc.Canny(gray, edges, 60.0, 180.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).track()
        Imgproc.dilate(edges, edges, kernel) // close gaps in the outline

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat().track(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val minArea = MIN_AREA_RATIO * small.cols() * small.rows()

        for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(10)) {
            if (Imgproc.contourArea(contour) < minArea) break
            val curve = MatOfPoint2f(*contour.toArray()).track()
            val approx = MatOfPoint2f().track()
            Imgproc.approxPolyDP(curve, approx, 0.02 * Imgproc.arcLength(curve, true), true)
            val pts = approx.toArray()
            if (pts.size == 4 && Imgproc.isContourConvex(MatOfPoint(*pts))) {
                return orderCorners(pts.map { Point(it.x / scale, it.y / scale) }.toTypedArray())
            }
        }
        return null
    }

    /** Order: top-left, top-right, bottom-right, bottom-left. */
    private fun orderCorners(p: Array<Point>): Array<Point> = arrayOf(
        p.minBy { it.x + it.y },
        p.maxBy { it.x - it.y },
        p.maxBy { it.x + it.y },
        p.minBy { it.x - it.y },
    )

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private fun MatScope.warp(rgba: Mat, corners: Array<Point>): Bitmap {
        val (tl, tr, br, bl) = corners
        val w = max(distance(tl, tr), distance(bl, br)).toInt().coerceAtLeast(1)
        val h = max(distance(tl, bl), distance(tr, br)).toInt().coerceAtLeast(1)
        val from = MatOfPoint2f(tl, tr, br, bl).track()
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0),
        ).track()
        val transform = Imgproc.getPerspectiveTransform(from, to).track()
        val flat = Mat().track()
        Imgproc.warpPerspective(rgba, flat, transform, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC)
        val out = createBitmap(w, h)
        Utils.matToBitmap(flat, out)
        return out
    }
}
