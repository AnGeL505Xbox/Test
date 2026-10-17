package com.example.ultrahdrwallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Displays an actual COLOR_MODE_HDR preview, separately from the HDR live-wallpaper experiment. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var diagnostic: TextView
    private lateinit var preview: ImageView
    private var previewBitmap: Bitmap? = null
    private val pickCode = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 35, 28, 20)
        }
        status = TextView(this).apply {
            textSize = 16f
            text = "Ultra HDR Wallpaper 0.2 — prueba experimental FP16/scRGB. Selecciona un JPEG Ultra HDR."
        }
        diagnostic = TextView(this).apply { textSize = 13f }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = false
        }
        val pick = Button(this).apply {
            text = "Seleccionar JPEG Ultra HDR"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/jpeg"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }, pickCode)
            }
        }
        val apply = Button(this).apply {
            text = "Aplicar como fondo de inicio (prueba HDR)"
            setOnClickListener {
                if (!getFileStreamPath("wallpaper.jpg").exists()) {
                    Toast.makeText(this@MainActivity, "Primero selecciona una imagen", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        ComponentName(this@MainActivity, HDRWallpaperService::class.java))
                })
            }
        }
        val refresh = Button(this).apply {
            text = "Actualizar diagnóstico del fondo"
            setOnClickListener { updateDiagnostics() }
        }
        layout.addView(status)
        layout.addView(pick)
        layout.addView(apply)
        layout.addView(refresh)
        layout.addView(diagnostic)
        layout.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        refreshPreview()
        updateDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        if (::diagnostic.isInitialized) updateDiagnostics()
    }

    private fun updateDiagnostics() {
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val ratio = if (display?.isHdrSdrRatioAvailable == true)
            "${"%.2f".format(display.hdrSdrRatio)}x" else "no disponible"
        val msg = getSharedPreferences("hdr_diag", MODE_PRIVATE)
            .getString("status", "Aún no hay resultado del motor de fondo.")
        diagnostic.text = "Pantalla HDR: ${display?.isHdr == true}. " +
            "HDR/SDR actual: $ratio.\nResultado del fondo: $msg\n" +
            "La vista inferior usa COLOR_MODE_HDR (solo esta Activity). " +
            "Que esa vista sea HDR NO demuestra que el wallpaper lo sea."
    }

    private fun refreshPreview() {
        preview.setImageBitmap(null)
        previewBitmap?.recycle()
        previewBitmap = null
        val file = getFileStreamPath("wallpaper.jpg")
        if (!file.exists()) return
        try {
            val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            previewBitmap = b
            preview.setImageBitmap(b)
            window.colorMode = if (b.hasGainmap())
                ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
            status.text = if (b.hasGainmap())
                "✓ Gain map Ultra HDR detectado (${b.width} × ${b.height}). " +
                    "La vista previa solicita HDR. Aplica el fondo y comprueba si conserva el brillo." 
                else "⚠ Este JPEG no tiene gain map. Se mostrará SDR."
        } catch (e: Exception) {
            status.text = "Error al abrir JPEG: ${e.message}"
        }
    }

    @Deprecated("Callback de selección compatible con API 34+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            contentResolver.openInputStream(uri)!!.use { input ->
                openFileOutput("wallpaper.jpg", MODE_PRIVATE).use { output -> input.copyTo(output) }
            }
            getSharedPreferences("hdr_diag", MODE_PRIVATE).edit()
                .putString("status", "Imagen cambiada; reaplica el fondo para probarla.").apply()
            refreshPreview()
            updateDiagnostics()
        } catch (e: Exception) {
            status.text = "Error al copiar JPEG: ${e.message}"
        }
    }

    override fun onDestroy() {
        preview.setImageBitmap(null)
        previewBitmap?.recycle()
        previewBitmap = null
        super.onDestroy()
    }
}
