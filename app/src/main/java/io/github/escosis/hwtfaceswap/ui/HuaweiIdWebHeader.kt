package io.github.escosis.hwtfaceswap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.escosis.hwtfaceswap.R
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The native host applies system/cutout insets once; navigation matches AboutScreen's TopAppBar slot. */
@Composable
internal fun HuaweiIdWebHeader(hint: String, onClose: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Box(Modifier.fillMaxWidth().height(TopAppBarDefaults.CollapsedHeight)) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = TopAppBarDefaults.NavigationIconPadding),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.huawei_id_web_close),
                    tint = colors.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = stringResource(R.string.huawei_id_web_title),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 72.dp),
            )
        }
        Text(
            text = hint,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        )
    }
}
