package io.github.escosis.hwtfaceswap.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.utils.RemovePlatformDialogDefaultEffects
import top.yukonga.miuix.kmp.utils.platformDialogProperties

/**
 * 与首次说明共用的居中宿主；Miuix 0.9.3 WindowDialog没有居中参数，小屏默认底部。
 * 使用真实原生Dialog、Miuix主题/卡片与有界内容，不伪造屏幕断点或平移底部命中区域。
 * 动画结束只报告hide完成，实际native child disposal由调用方的owner收据核对。
 */
@Composable
internal fun CenteredIntroductionHost(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val latestOnDismissFinished by rememberUpdatedState(onDismissFinished)
    val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
    LaunchedEffect(show) {
        if (show) {
            visible = true
            progress.animateTo(1f, tween(260))
        } else if (visible) {
            progress.animateTo(0f, tween(260))
            visible = false
            // 此callback先于随后的Dialog disposal，不能直接启动连接或系统权限。
            latestOnDismissFinished()
        }
    }
    if (!visible) return
    Dialog(
        onDismissRequest = { latestOnDismissRequest() },
        properties = platformDialogProperties(),
    ) {
        RemovePlatformDialogDefaultEffects()
        val fraction = progress.value.coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.32f * fraction)))
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier.widthIn(max = DialogDefaults.MaxWidth).fillMaxWidth()
                        .heightIn(max = maxHeight).graphicsLayer {
                            alpha = fraction
                            scaleX = 0.9f + 0.1f * fraction
                            scaleY = 0.9f + 0.1f * fraction
                        },
                    cornerRadius = 32.dp,
                    insideMargin = PaddingValues(24.dp),
                    colors = CardDefaults.defaultColors(
                        color = DialogDefaults.backgroundColor(),
                        contentColor = DialogDefaults.titleColor(),
                    ),
                    content = { content() },
                )
            }
        }
    }
}
