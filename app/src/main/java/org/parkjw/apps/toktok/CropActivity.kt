package org.parkjw.apps.toktok

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * Crop editor: drag to pan, pinch to zoom. The circular overlay shows exactly
 * what will become the floating button. Live circular preview at the bottom.
 */
class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val RESULT_PATH = "path"
    }

    private lateinit var imageView: ImageView
    private lateinit var overlay: CropOverlayView
    private lateinit var preview: ImageView
    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private var lastX = 0f
    private var lastY = 0f
    private var lastDist = 0f
    private var mode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        title = getString(R.string.crop_title)

        imageView = findViewById(R.id.cropImage)
        overlay = findViewById(R.id.cropOverlay)
        preview = findViewById(R.id.cropPreview)
        preview.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        preview.clipToOutline = true

        val uri = intent.getParcelableExtra<Uri>(EXTRA_URI)
        if (uri == null) {
            finish()
            return
        }
        try {
            contentResolver.openInputStream(uri)?.use {
                bitmap = BitmapFactory.decodeStream(it)
            }
        } catch (_: Exception) {
        }
        val src = bitmap
        if (src == null) {
            Toast.makeText(this, R.string.error_fmt, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        imageView.setImageBitmap(src)

        // Fit image to view on first layout
        imageView.post {
            val vw = imageView.width.toFloat()
            val vh = imageView.height.toFloat()
            if (vw > 0 && vh > 0) {
                val scale = maxOf(vw / src.width, vh / src.height) * 1.2f
                matrix.setScale(scale, scale)
                matrix.postTranslate(
                    (vw - src.width * scale) / 2,
                    (vh - src.height * scale) / 2
                )
                imageView.imageMatrix = matrix
                updatePreview()
            }
        }

        val touch = View.OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = 1
                    lastX = ev.x
                    lastY = ev.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    mode = 2
                    lastDist = dist(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 1 && ev.pointerCount == 1) {
                        matrix.postTranslate(ev.x - lastX, ev.y - lastY)
                        lastX = ev.x
                        lastY = ev.y
                    } else if (mode == 2 && ev.pointerCount == 2) {
                        val d = dist(ev)
                        if (lastDist > 0) {
                            val s = (d / lastDist).coerceIn(0.8f, 1.25f)
                            matrix.postScale(
                                s, s,
                                (ev.getX(0) + ev.getX(1)) / 2,
                                (ev.getY(0) + ev.getY(1)) / 2
                            )
                        }
                        lastDist = d
                    }
                    imageView.imageMatrix = matrix
                    updatePreview()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
                MotionEvent.ACTION_POINTER_UP -> {
                    mode = 1
                    lastX = ev.x
                    lastY = ev.y
                }
            }
            true
        }
        imageView.setOnTouchListener(touch)
        // Overlay is on top: forward touches to the image
        overlay.setOnTouchListener(touch)

        findViewById<Button>(R.id.cropDone).setOnClickListener { saveCrop() }
    }

    private fun dist(ev: MotionEvent): Float {
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Render what the circular crop will look like into the preview. */
    private fun updatePreview() {
        val src = bitmap ?: return
        val vw = imageView.width
        val vh = imageView.height
        if (vw <= 0 || vh <= 0) return
        try {
            val (cx, cy, r) = overlay.cropCircle().let { Triple(it[0], it[1], it[2]) }
            val size = (r * 2).toInt().coerceAtLeast(1)
            // Render current transform
            val rendered = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            Canvas(rendered).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            // Extract circle area
            val x0 = (cx - r).toInt().coerceIn(0, vw - size)
            val y0 = (cy - r).toInt().coerceIn(0, vh - size)
            val square = Bitmap.createBitmap(rendered, x0, y0, size, size)
            rendered.recycle()
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            c.drawBitmap(square, 0f, 0f, p)
            square.recycle()
            preview.setImageBitmap(out)
        } catch (_: Exception) {
        }
    }

    private fun saveCrop() {
        val src = bitmap ?: return
        try {
            val vw = imageView.width
            val vh = imageView.height
            if (vw <= 0 || vh <= 0) return
            val (cx, cy, r) = overlay.cropCircle().let { Triple(it[0], it[1], it[2]) }
            val size = (r * 2).toInt().coerceAtLeast(1)
            val rendered = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            Canvas(rendered).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            val x0 = (cx - r).toInt().coerceIn(0, vw - size)
            val y0 = (cy - r).toInt().coerceIn(0, vh - size)
            val square = Bitmap.createBitmap(rendered, x0, y0, size, size)
            rendered.recycle()
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            c.drawBitmap(square, 0f, 0f, p)
            square.recycle()

            val file = File(filesDir, "button_image.png")
            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.recycle()
            intent.putExtra(RESULT_PATH, file.absolutePath)
            setResult(RESULT_OK, intent)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_fmt, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        bitmap?.recycle()
        super.onDestroy()
    }
}
