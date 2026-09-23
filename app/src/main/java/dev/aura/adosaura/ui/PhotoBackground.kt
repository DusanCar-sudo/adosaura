package dev.aura.adosaura.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.aura.adosaura.config.ConfigFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * `wallpaper=photo`: a picture from the gallery behind the tiles.
 *
 * The picked photo is copied to /sdcard/adosaura/background.jpg, scaled
 * to the screen, so it survives the gallery deleting the original, travels
 * with a save, and never costs a full-resolution decode at runtime.
 */
object Photo {

    val file: File get() = File(ConfigFiles.dir, ConfigFiles.BACKGROUND_NAME)

    /** Copy [uri] into [file], scaled so its short side is about [target] px. */
    fun import(context: Context, uri: Uri, target: Int): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val short = minOf(bounds.outWidth, bounds.outHeight)
        if (short <= 0) return false
        var sample = 1
        while (short / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
        ConfigFiles.dir.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        tmp.renameTo(file)
    }.getOrDefault(false)
}

/** The photo, cropped to fill; nothing at all when there is none. */
@Composable
fun PhotoBackground(stamp: Long) {
    val image by produceState<ImageBitmap?>(null, stamp) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(Photo.file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    image?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
