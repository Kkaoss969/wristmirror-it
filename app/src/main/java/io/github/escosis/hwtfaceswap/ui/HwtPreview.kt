package io.github.escosis.hwtfaceswap.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.escosis.hwtfaceswap.SourceInfo
import io.github.escosis.hwtfaceswap.protocol.Core
import io.github.escosis.hwtfaceswap.protocol.HwtSource
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun previewDimensionsAllowed(width: Int, height: Int): Boolean =
    width in 1..4096 && height in 1..4096 && width.toLong() * height <= 4L * 1024 * 1024

internal fun previewSampleSize(width: Int, height: Int): Int {
    if (!previewDimensionsAllowed(width, height)) return 1
    var sample = 1
    while (true) {
        val sampledWidth = (width.toLong() + sample - 1) / sample
        val sampledHeight = (height.toLong() + sample - 1) / sample
        if (sampledWidth <= 1024 && sampledHeight <= 1024 && sampledWidth * sampledHeight <= 1024L * 1024) return sample
        sample *= 2
    }
}

/** 只接受由本机导入器生成的直接预览旁路文件，不打开 URI 或外部图片路径。 */
internal fun allowedPreviewFile(privateFiles: File, path: String): File? = try {
    val root = File(privateFiles, "local-hwt").canonicalFile
    val file = File(path).canonicalFile
    val directory = file.parentFile
    if (directory != null && directory.parentFile == root && directory.name.matches(Regex("source-[0-9a-f-]{36}")) &&
        file.name == "preview.image") file else null
} catch (_: Exception) { null }

private fun loadPreview(privateFiles: File, path: String, expectedHash: String): ImageBitmap? {
    if (!expectedHash.matches(Regex("[0-9a-f]{64}"))) return null
    val file = allowedPreviewFile(privateFiles, path) ?: return null
    if (!file.isFile || file.length() !in 1..HwtSource.MAX_PREVIEW_BYTES.toLong()) return null
    val bytes = FileInputStream(file).use { Core.read(it, HwtSource.MAX_PREVIEW_BYTES) }
    if (Core.hex(MessageDigest.getInstance("SHA-256").digest(bytes)) != expectedHash) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (!previewDimensionsAllowed(bounds.outWidth, bounds.outHeight)) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = previewSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }
    // Bounds 与解码使用同一份有界字节快照，不在两次解码之间重新读取文件。
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data object Unavailable : PreviewState
    data class Ready(val image: ImageBitmap) : PreviewState
}

@Composable
internal fun HwtPreview(source: SourceInfo) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.size(128.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val path = source.previewPath
        val hash = source.previewSha256
        if (path == null || hash == null) {
            MutedText("L'HWT non contiene un'anteprima utilizzabile")
        } else {
            val files = LocalContext.current.applicationContext.filesDir
            val state by produceState<PreviewState>(PreviewState.Loading, files, path, hash) {
                value = withContext(Dispatchers.IO) {
                    try { loadPreview(files, path, hash)?.let { PreviewState.Ready(it) } ?: PreviewState.Unavailable }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { PreviewState.Unavailable }
                }
            }
            Box(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                when (val current = state) {
                    PreviewState.Loading -> MutedText("Lettura anteprima…")
                    PreviewState.Unavailable -> MutedText("Anteprima non disponibile")
                    is PreviewState.Ready -> Image(
                        bitmap = current.image,
                        contentDescription = "Anteprima dell'HWT selezionato",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}
