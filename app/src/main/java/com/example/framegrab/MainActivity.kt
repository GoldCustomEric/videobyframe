package com.example.framegrab

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var image: ImageView
    private lateinit var seek: SeekBar
    private lateinit var info: TextView

    private var retriever: MediaMetadataRetriever? = null
    private var fps = 30.0
    private var frameCount = 0
    private var index = 0
    private var current: Bitmap? = null
    private var requestId = 0
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(8), dp(24), dp(8), dp(12))
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(btn("Открыть видео") { openPicker() })
        top.addView(btn("Сохранить кадр") { saveFrame() })
        root.addView(top)

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(image)

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = "Откройте видеофайл"
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(info)

        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) showFrame(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(seek)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(btn("−10") { showFrame(index - 10) })
        bottom.addView(btn("◀ −1") { showFrame(index - 1) })
        bottom.addView(btn("+1 ▶") { showFrame(index + 1) })
        bottom.addView(btn("+10") { showFrame(index + 10) })
        root.addView(bottom)

        setContentView(root)
    }

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            data?.data?.let { loadVideo(it) }
        }
    }

    private fun loadVideo(uri: Uri) {
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(this, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull() ?: 0
            val capFps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull() ?: 0.0

            fps = when {
                frames > 0 && durMs > 0 -> frames * 1000.0 / durMs
                capFps > 0 -> capFps
                else -> 30.0
            }
            frameCount = if (frames > 0) frames else max(1, (durMs / 1000.0 * fps).toInt())

            requestId++
            retriever?.release()
            retriever = r

            seek.max = max(0, frameCount - 1)
            showFrame(0)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть видео: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFrame(i: Int) {
        val r = retriever ?: return
        val idx = min(max(i, 0), frameCount - 1)
        index = idx
        seek.progress = idx
        val id = ++requestId
        val timeUs = ((idx + 0.5) * 1_000_000.0 / fps).toLong()
        updateInfo()
        executor.execute {
            val bmp = try {
                r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (id == requestId && bmp != null) {
                    current = bmp
                    image.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun updateInfo() {
        val t = index / fps
        info.text = String.format(
            "Кадр %d / %d   •   %.3f с   •   %.2f fps",
            index + 1, frameCount, t, fps
        )
    }

    private fun saveFrame() {
        val bmp = current
        if (bmp == null) {
            Toast.makeText(this, "Сначала откройте видео", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "frame_${index + 1}_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FrameGrab")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Toast.makeText(this, "Сохранено в Pictures/FrameGrab", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Кнопки громкости тоже листают кадры
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { showFrame(index + 1); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { showFrame(index - 1); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        retriever?.release()
    }
}
