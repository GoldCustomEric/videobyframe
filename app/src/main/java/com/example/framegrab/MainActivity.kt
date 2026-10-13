package com.example.framegrab

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var image: ImageView
    private lateinit var video: VideoView
    private lateinit var seek: SeekBar
    private lateinit var info: TextView
    private lateinit var playBtn: Button

    private var retriever: MediaMetadataRetriever? = null
    private var uri: Uri? = null
    private var fps = 30.0
    private var frameCount = 0
    private var index = 0
    private var current: Bitmap? = null
    private var requestId = 0
    private var playing = false
    private var previewW = 0
    private var previewH = 0

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing) return
            val idx = min(max((video.currentPosition / 1000.0 * fps).toInt(), 0), frameCount - 1)
            index = idx
            seek.progress = idx
            updateInfo()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        minWidth = 0
        minimumWidth = 0
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
        top.addView(btn("Открыть") { openPicker() })
        top.addView(btn("Удалить видео") { confirmDelete() })
        top.addView(btn("Сохранить кадр") { saveFrame() })
        root.addView(top)

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        frame.addView(
            image,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        video = VideoView(this).apply { visibility = View.GONE }
        frame.addView(
            video,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        root.addView(frame)

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
                    if (fromUser) showFrame(p, preview = true)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    pausePlayback()
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    showFrame(sb.progress)
                }
            })
        }
        root.addView(seek)

        playBtn = btn("▶") { togglePlay() }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(btn("−10") { step(-10) })
        bottom.addView(btn("−1") { step(-1) })
        bottom.addView(playBtn)
        bottom.addView(btn("+1") { step(1) })
        bottom.addView(btn("+10") { step(10) })
        root.addView(bottom)

        setContentView(root)
    }

    // ---------- Открытие ----------

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            data?.data?.let { loadVideo(it) }
        } else if (requestCode == 2 && resultCode == RESULT_OK) {
            clearVideo()
            Toast.makeText(this, "Видео удалено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun releaseRetriever() {
        val old = retriever
        retriever = null
        if (old != null) {
            executor.execute {
                try {
                    old.release()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun loadVideo(newUri: Uri) {
        try {
            pausePlayback()
            val r = MediaMetadataRetriever()
            r.setDataSource(this, newUri)
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

            // Размер маленького превью для быстрой перемотки ползунком
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val t = w; w = h; h = t
            }
            if (w > 0 && h > 0) {
                previewW = min(480, w)
                previewH = max(1, (previewW.toLong() * h / w).toInt())
            } else {
                previewW = 0
                previewH = 0
            }

            requestId++
            releaseRetriever()
            retriever = r
            uri = newUri
            current = null

            seek.max = max(0, frameCount - 1)
            showFrame(0)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть видео: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Показ кадров ----------

    private fun showFrame(i: Int, preview: Boolean = false) {
        val r = retriever ?: return
        val idx = min(max(i, 0), frameCount - 1)
        index = idx
        seek.progress = idx
        updateInfo()
        val id = ++requestId
        val timeUs = ((idx + 0.5) * 1_000_000.0 / fps).toLong()
        val pw = previewW
        val ph = previewH
        executor.execute {
            // Если пользователь уже ушёл дальше — не тратим время на устаревший кадр
            if (id != requestId) return@execute
            val bmp = try {
                if (preview && pw > 0) {
                    r.getScaledFrameAtTime(
                        timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, pw, ph
                    )
                } else {
                    r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (id == requestId && bmp != null) {
                    if (!preview) current = bmp
                    image.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun step(delta: Int) {
        pausePlayback()
        showFrame(index + delta)
    }

    private fun updateInfo() {
        val t = index / fps
        info.text = String.format(
            "Кадр %d / %d   •   %.3f с   •   %.2f fps",
            index + 1, frameCount, t, fps
        )
    }

    // ---------- Воспроизведение ----------

    private fun togglePlay() {
        if (retriever == null) return
        if (playing) pausePlayback() else startPlayback()
    }

    private fun startPlayback() {
        val u = uri ?: return
        if (playing) return
        playing = true
        playBtn.text = "⏸"
        requestId++ // отменяем незавершённые загрузки кадров
        val startIdx = if (index >= frameCount - 1) 0 else index
        video.visibility = View.VISIBLE
        video.setOnCompletionListener { pausePlayback() }
        video.setOnErrorListener { _, _, _ ->
            pausePlayback()
            true
        }
        video.setVideoURI(u)
        video.seekTo(((startIdx + 0.5) * 1000.0 / fps).toInt())
        video.start()
        handler.post(ticker)
    }

    private fun pausePlayback() {
        if (!playing) return
        playing = false
        handler.removeCallbacks(ticker)
        playBtn.text = "▶"
        val pos = try {
            video.currentPosition
        } catch (e: Exception) {
            0
        }
        video.stopPlayback()
        video.visibility = View.GONE
        showFrame((pos / 1000.0 * fps).toInt())
    }

    // ---------- Сохранение кадра ----------

    private fun saveFrame() {
        if (playing) {
            Toast.makeText(this, "Поставьте видео на паузу", Toast.LENGTH_SHORT).show()
            return
        }
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
        val out = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (out == null) {
            Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            resolver.openOutputStream(out)?.use { os ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(out, values, null, null)
            Toast.makeText(this, "Сохранено в Pictures/FrameGrab", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            resolver.delete(out, null, null)
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Удаление видео ----------

    private fun confirmDelete() {
        if (uri == null) {
            Toast.makeText(this, "Сначала откройте видео", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Удалить видео?")
            .setMessage("Файл будет удалён с устройства безвозвратно.")
            .setPositiveButton("Удалить") { _, _ -> deleteCurrent() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteCurrent() {
        val u = uri ?: return
        pausePlayback()

        var ok = false
        try {
            ok = DocumentsContract.deleteDocument(contentResolver, u)
        } catch (e: Exception) {
        }
        if (ok) {
            clearVideo()
            Toast.makeText(this, "Видео удалено", Toast.LENGTH_SHORT).show()
            return
        }

        // Запасной путь для Android 11+: системный диалог подтверждения удаления
        if (Build.VERSION.SDK_INT >= 30 && u.authority == "com.android.providers.media.documents") {
            try {
                val docId = DocumentsContract.getDocumentId(u)
                if (docId.startsWith("video:")) {
                    val mediaUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        docId.substringAfter(':').toLong()
                    )
                    val pi = MediaStore.createDeleteRequest(contentResolver, listOf(mediaUri))
                    startIntentSenderForResult(pi.intentSender, 2, null, 0, 0, 0)
                    return
                }
            } catch (e: Exception) {
            }
        }
        Toast.makeText(
            this,
            "Система не разрешила удалить этот файл. Удалите его через Галерею или Файлы.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun clearVideo() {
        pausePlayback()
        requestId++
        releaseRetriever()
        uri = null
        current = null
        frameCount = 0
        index = 0
        image.setImageDrawable(null)
        seek.max = 0
        seek.progress = 0
        info.text = "Откройте видеофайл"
    }

    // Кнопки громкости тоже листают кадры
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { step(1); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { step(-1); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() {
        super.onPause()
        pausePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        video.stopPlayback()
        releaseRetriever()
        executor.shutdown()
    }
}
