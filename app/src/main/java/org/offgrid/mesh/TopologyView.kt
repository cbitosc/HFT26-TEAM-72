package org.offgrid.mesh

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.pow

/**
 * Interactive local-radio map with pinch-to-zoom and pan.
 * Only peers discovered by this phone are drawn — never synthetic nodes.
 */
class TopologyView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var ownId = "THIS DEVICE"
    private var peers: List<RadioMeshManager.Peer> = emptyList()

    // Zoom & pan state
    private var scaleFactor = 1.0f
    private var panX = 0f
    private var panY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.5f, 5.0f)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleFactor > 1.0f) {
                panX -= dx
                panY -= dy
                invalidate()
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Double-tap to zoom in/out
            scaleFactor = if (scaleFactor > 1.5f) 1.0f else 2.5f
            invalidate()
            return true
        }
    })

    fun update(device: String, items: List<RadioMeshManager.Peer>) {
        ownId = device
        peers = items
        invalidate()
    }

    fun adjustZoom(factor: Float) {
        scaleFactor = (scaleFactor * factor).coerceIn(0.5f, 5.0f)
        invalidate()
    }

    fun resetZoom() {
        scaleFactor = 1.0f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun estimatedMetres(rssi: Int?): Int? {
        if (rssi == null) return null
        return (10.0.pow((-59 - rssi) / 20.0)).toInt().coerceIn(1, 60)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (scaleFactor > 1.0f) {
                        panX += dx
                        panY += dy
                        invalidate()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()

        c.save()
        c.translate(panX, panY)
        c.scale(scaleFactor, scaleFactor)

        // Background
        c.drawColor(Color.rgb(243, 238, 223))

        // Grid
        paint.color = Color.rgb(201, 193, 176)
        paint.strokeWidth = 1f / scaleFactor
        val gridSize = 40
        val startX = (panX / scaleFactor).toInt() - (panX / scaleFactor).toInt() % gridSize - gridSize
        val startY = (panY / scaleFactor).toInt() - (panY / scaleFactor).toInt() % gridSize - gridSize
        val endX = startX + (w / scaleFactor).toInt() + gridSize * 2
        val endY = startY + (h / scaleFactor).toInt() + gridSize * 2
        for (x in startX..endX step gridSize) c.drawLine(x.toFloat(), startY.toFloat(), x.toFloat(), endY.toFloat(), paint)
        for (y in startY..endY step gridSize) c.drawLine(startX.toFloat(), y.toFloat(), endX.toFloat(), y.toFloat(), paint)

        val cx = w / 2
        val cy = h / 2
        val baseRadius = (minOf(w, h) * 0.35f).coerceAtLeast(58f)

        // Draw connections first (behind nodes)
        peers.take(10).forEachIndexed { index, peer ->
            val angle = (Math.PI * 2 * index / peers.take(10).size.coerceAtLeast(1) - Math.PI / 2).toFloat()
            val estimated = estimatedMetres(peer.rssiDbm)
            val visualRadius = estimated?.let { (it * 7f).coerceIn(58f, baseRadius) } ?: baseRadius
            val x = cx + (kotlin.math.cos(angle.toDouble()) * visualRadius).toFloat()
            val y = cy + (kotlin.math.sin(angle.toDouble()) * visualRadius).toFloat()

            // Connection line
            paint.color = if (peer.connected) Color.rgb(21, 94, 239) else Color.rgb(138, 134, 123)
            paint.strokeWidth = if (peer.connected) 4f else 2f
            paint.alpha = if (peer.connected) 255 else 140
            c.drawLine(cx, cy, x, y, paint)
            paint.alpha = 255

            // Range circle (subtle)
            if (estimated != null) {
                paint.color = if (peer.connected) Color.rgb(21, 94, 239) else Color.rgb(138, 134, 123)
                paint.alpha = 20
                paint.style = Paint.Style.FILL
                c.drawCircle(x, y, (estimated * 3f).coerceAtLeast(20f), paint)
                paint.alpha = 255
                paint.style = Paint.Style.FILL
            }
        }

        // Draw nodes
        peers.take(10).forEachIndexed { index, peer ->
            val angle = (Math.PI * 2 * index / peers.take(10).size.coerceAtLeast(1) - Math.PI / 2).toFloat()
            val estimated = estimatedMetres(peer.rssiDbm)
            val visualRadius = estimated?.let { (it * 7f).coerceIn(58f, baseRadius) } ?: baseRadius
            val x = cx + (kotlin.math.cos(angle.toDouble()) * visualRadius).toFloat()
            val y = cy + (kotlin.math.sin(angle.toDouble()) * visualRadius).toFloat()

            // Node shadow
            paint.color = Color.argb(40, 0, 0, 0)
            paint.style = Paint.Style.FILL
            c.drawCircle(x + 2f, y + 3f, 32f, paint)

            // Node circle (32px radius so names fit)
            val nodeRadius = 32f
            paint.color = if (peer.connected) Color.rgb(21, 94, 239) else Color.rgb(251, 248, 239)
            c.drawCircle(x, y, nodeRadius, paint)

            // Node border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = if (peer.connected) Color.rgb(11, 59, 170) else Color.rgb(21, 21, 21)
            c.drawCircle(x, y, nodeRadius, paint)
            paint.style = Paint.Style.FILL

            // Node label (larger)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 12f
            paint.color = if (peer.connected) Color.WHITE else Color.rgb(21, 21, 21)
            val shortLabel = if (peer.label.length > 12) peer.label.take(11) + "…" else peer.label
            c.drawText(shortLabel, x, y + 4f, paint)

            // Transport + RSSI below node (larger)
            paint.textSize = 10f
            paint.color = if (peer.connected) Color.rgb(200, 220, 255) else Color.rgb(119, 115, 106)
            val info = peer.transport.replace("Wi‑Fi Direct", "WiFi") + (estimated?.let { " · ~${it}m" } ?: "")
            c.drawText(info, x, y + 46f, paint)
        }

        // Draw center node (this device)
        paint.color = Color.argb(60, 0, 0, 0)
        paint.style = Paint.Style.FILL
        c.drawCircle(cx + 3f, cy + 3f, 26f, paint)

        val centerSize = 36f
        paint.color = Color.rgb(21, 21, 21)
        c.drawRoundRect(cx - centerSize, cy - centerSize, cx + centerSize, cy + centerSize, 8f, 8f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.rgb(21, 94, 239)
        c.drawRoundRect(cx - centerSize, cy - centerSize, cx + centerSize, cy + centerSize, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 14f
        paint.color = Color.rgb(243, 238, 223)
        c.drawText("YOU", cx, cy + 6f, paint)

        paint.textSize = 12f
        paint.color = Color.rgb(255, 255, 255)
        c.drawText(ownId.take(12), cx, cy + 48f, paint)

        // Zoom indicator
        if (scaleFactor > 1.01f) {
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = 10f
            paint.color = Color.rgb(119, 115, 106)
            c.drawText("${(scaleFactor * 100).toInt()}%  ·  pinch to zoom  ·  drag to pan", w - 12f, h - 12f, paint)
        }

        c.restore()
    }
}
