package io.github.escosis.hwtfaceswap.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.escosis.hwtfaceswap.ThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 使用参考项目相同的默认 Miuix 配色及外观配置覆盖。 */
@Composable
fun WanJingTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val systemIsDark = isSystemInDarkTheme()
    val isDark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemIsDark
    }
    val configuration = LocalConfiguration.current
    val themedConfiguration = remember(configuration, isDark) {
        Configuration(configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
    }
    val schemeMode = when (mode) {
        ThemeMode.LIGHT -> ColorSchemeMode.Light
        ThemeMode.DARK -> ColorSchemeMode.Dark
        ThemeMode.SYSTEM -> ColorSchemeMode.System
    }
    val controller = remember(mode) { ThemeController(colorSchemeMode = schemeMode) }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }
    CompositionLocalProvider(LocalConfiguration provides themedConfiguration) {
        MiuixTheme(controller = controller, content = content)
    }
}
