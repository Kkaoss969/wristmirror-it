package io.github.escosis.hwtfaceswap.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

/** 每页独立使用 KernelSU 同款 Miuix 收起/展开行为，默认小标题居中。 */
@Composable
internal fun CollapsingPage(
    title: String,
    fullscreen: Boolean = false,
    navigationIcon: @Composable () -> Unit = {},
    content: @Composable (PaddingValues, Modifier) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val sides = if (fullscreen) WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        else WindowInsetsSides.Horizontal
    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = navigationIcon,
                scrollBehavior = scrollBehavior,
            )
        },
        // 分页的菜单由最外层 Scaffold 承载，避免预加载页重复绘制同一弹层。
        popupHost = {},
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(sides),
    ) { insets ->
        content(insets, Modifier.nestedScroll(scrollBehavior.nestedScrollConnection))
    }
}

@Composable
internal fun pageContentPadding(insets: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = insets.calculateStartPadding(direction) + 12.dp,
        top = insets.calculateTopPadding() + 12.dp,
        end = insets.calculateEndPadding(direction) + 12.dp,
        bottom = insets.calculateBottomPadding() + 12.dp,
    )
}
