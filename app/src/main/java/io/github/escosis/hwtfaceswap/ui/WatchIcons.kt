package io.github.escosis.hwtfaceswap.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 自绘紧凑轮廓图标，用于界面装饰，不模拟手表预览。 */
internal object WatchIcons {
    val Watch = outline("Watch") {
        moveTo(8f, 5f); lineTo(8.8f, 1.5f); lineTo(15.2f, 1.5f); lineTo(16f, 5f)
        moveTo(8f, 19f); lineTo(8.8f, 22.5f); lineTo(15.2f, 22.5f); lineTo(16f, 19f)
        moveTo(7f, 5f); lineTo(17f, 5f); curveTo(18.65f, 5f, 20f, 6.35f, 20f, 8f)
        lineTo(20f, 16f); curveTo(20f, 17.65f, 18.65f, 19f, 17f, 19f)
        lineTo(7f, 19f); curveTo(5.35f, 19f, 4f, 17.65f, 4f, 16f)
        lineTo(4f, 8f); curveTo(4f, 6.35f, 5.35f, 5f, 7f, 5f); close()
        moveTo(12f, 8f); lineTo(12f, 12f); lineTo(15f, 14f)
        moveTo(22f, 9f); lineTo(22f, 12f)
    }
    val Faces = outline("Faces") {
        moveTo(5f, 3f); lineTo(10f, 3f); lineTo(10f, 10f); lineTo(3f, 10f)
        lineTo(3f, 5f); curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f); close()
        moveTo(14f, 3f); lineTo(19f, 3f); curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
        lineTo(21f, 10f); lineTo(14f, 10f); close()
        moveTo(3f, 14f); lineTo(10f, 14f); lineTo(10f, 21f); lineTo(5f, 21f)
        curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f); close()
        moveTo(14f, 14f); lineTo(21f, 14f); lineTo(21f, 19f)
        curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f); lineTo(14f, 21f); close()
    }
    val Search = outline("Search") {
        moveTo(17f, 10f); curveTo(17f, 13.87f, 13.87f, 17f, 10f, 17f)
        curveTo(6.13f, 17f, 3f, 13.87f, 3f, 10f)
        curveTo(3f, 6.13f, 6.13f, 3f, 10f, 3f)
        curveTo(13.87f, 3f, 17f, 6.13f, 17f, 10f); close()
        moveTo(15.2f, 15.2f); lineTo(21f, 21f)
    }
    val Shield = outline("Shield") {
        moveTo(12f, 2f); lineTo(20f, 5f); lineTo(20f, 11f)
        curveTo(20f, 16f, 16.8f, 19.9f, 12f, 22f)
        curveTo(7.2f, 19.9f, 4f, 16f, 4f, 11f); lineTo(4f, 5f); close()
        moveTo(8f, 11.5f); lineTo(11f, 14.5f); lineTo(16f, 9.5f)
    }
    val File = outline("HwtFile") {
        moveTo(6f, 2f); lineTo(14f, 2f); lineTo(20f, 8f); lineTo(20f, 20f)
        curveTo(20f, 21.1f, 19.1f, 22f, 18f, 22f); lineTo(6f, 22f)
        curveTo(4.9f, 22f, 4f, 21.1f, 4f, 20f); lineTo(4f, 4f)
        curveTo(4f, 2.9f, 4.9f, 2f, 6f, 2f); close()
        moveTo(14f, 2f); lineTo(14f, 8f); lineTo(20f, 8f)
        moveTo(8f, 13f); lineTo(16f, 13f); moveTo(8f, 17f); lineTo(13f, 17f)
    }
    val Expand = outline("Expand") {
        moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
    }
    private fun outline(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()
}
