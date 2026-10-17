package com.example.ultrahdrwallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/** Experimental: Android's hardware Canvas may preserve gain maps, but the compositor may tone-map. */
class HDRWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = HDREngine()
    inner class HDREngine : Engine() {
        private var bitmap: Bitmap? = null
        private var visible = false
        override fun onCreate(surfaceHolder: SurfaceHolder) { super.onCreate(surfaceHolder); load() }
        private fun load() {
            try {
                val file = getFileStreamPath("wallpaper.jpg")
                if (!file.exists()) return
                bitmap?.recycle()
                bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (_: Exception) { bitmap = null }
        }
        override fun onVisibilityChanged(isVisible: Boolean) { visible = isVisible; if (visible) { load(); drawFrame() } }
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { super.onSurfaceChanged(holder, format, width, height); drawFrame() }
        private fun drawFrame() {
            if (!surfaceHolder.surface.isValid) return
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockHardwareCanvas()
                canvas.drawColor(Color.BLACK)
                bitmap?.let { b ->
                    val w = canvas.width.toFloat(); val h = canvas.height.toFloat()
                    val scale = maxOf(w / b.width, h / b.height)
                    val bw = b.width * scale; val bh = b.height * scale
                    canvas.drawBitmap(b, null, RectF((w - bw)/2, (h - bh)/2, (w + bw)/2, (h + bh)/2), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            } catch (_: Exception) { } finally { if (canvas != null) try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Exception) {} }
        }
        override fun onDestroy() { bitmap?.recycle(); bitmap = null; super.onDestroy() }
    }
}
