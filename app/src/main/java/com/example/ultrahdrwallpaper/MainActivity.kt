package com.example.ultrahdrwallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val pickCode = 12
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 30) }
        status = TextView(this).apply { text = "Selecciona un JPEG Ultra HDR. Requiere Android 14+. La salida HDR del fondo depende de One UI."; textSize = 17f }
        val pick = Button(this).apply { text = "Seleccionar JPEG Ultra HDR"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/jpeg"; addCategory(Intent.CATEGORY_OPENABLE) }, pickCode)
        } }
        val apply = Button(this).apply { text = "Elegir como fondo de inicio"; setOnClickListener {
            if (!getFileStreamPath("wallpaper.jpg").exists()) { Toast.makeText(this@MainActivity, "Selecciona una imagen primero", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply { putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, android.content.ComponentName(this@MainActivity, HDRWallpaperService::class.java)) })
        } }
        layout.addView(status); layout.addView(pick); layout.addView(apply); setContentView(layout)
    }
    @Deprecated("Legacy result callback for compact sample")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            contentResolver.openInputStream(uri)!!.use { input -> openFileOutput("wallpaper.jpg", MODE_PRIVATE).use { input.copyTo(it) } }
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getFileStreamPath("wallpaper.jpg"))) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            val gain = bmp.hasGainmap()
            status.text = if (gain) "✓ Gain map Ultra HDR detectado (${bmp.width} × ${bmp.height}). Listo para aplicar. HDR en el fondo: pendiente de verificar en tu Fold8." else "⚠ No se detectó gain map. La imagen puede ser SDR."
            bmp.recycle()
        } catch (e: Exception) { status.text = "Error: ${e.message}" }
    }
}
