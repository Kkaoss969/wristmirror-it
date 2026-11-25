package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.FaceItem
import io.github.escosis.hwtfaceswap.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 最新值来自页面 owner，而非某个可能尚未重组的 LazyColumn 子项。 */
internal class FaceActionHost(
    val state: () -> AppUiState,
    val actions: () -> AppActions,
    val context: () -> Context,
    val lifecycleOwner: () -> LifecycleOwner,
    val windowInfo: () -> WindowInfo,
    val pickHwt: () -> (() -> Unit),
    val ownerCurrent: () -> Boolean,
)

internal class FaceUiInteraction(
    val modifier: Modifier,
    val context: Context,
    val guard: () -> FaceCardInteractionGuard,
)

// A referential stamp, not a data class: rememberUpdatedState must replace even equal policy frames.
private class FaceUiRenderStamp(val frame: Any)

/** 同一个真实控件的坐标与 owner 同时保护 enabled 回调和 disabled Initial-pass 提示。 */
@Composable
internal fun rememberFaceUiInteraction(
    frame: Any,
    host: FaceActionHost,
    renderedActions: AppActions,
    ownerCurrent: () -> Boolean = { true },
    intentCurrent: () -> Boolean = { true },
): FaceUiInteraction {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowInfo = LocalWindowInfo.current
    val renderStamp = FaceUiRenderStamp(frame)
    val latestRenderStamp by rememberUpdatedState(renderStamp)
    val owner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(owner) {
        onDispose {
            owner.set(false)
            coordinates[0] = null
        }
    }
    return FaceUiInteraction(
        modifier = Modifier.onGloballyPositioned { coordinates[0] = it },
        context = context,
        guard = {
            val placed = coordinates[0]
            val attached = placed?.isAttached == true
            val activity = context.faceUiActivity()
            FaceCardInteractionGuard(
                ownerAlive = owner.get() && host.ownerCurrent() && ownerCurrent() &&
                    activity?.isFinishing != true && activity?.isDestroyed != true,
                frameCurrent = latestRenderStamp === renderStamp && latestRenderStamp.frame === frame,
                actionsCurrent = host.actions() === renderedActions,
                contextCurrent = host.context() === context,
                lifecycleOwnerCurrent = host.lifecycleOwner() === lifecycleOwner,
                windowOwnerCurrent = host.windowInfo() === windowInfo,
                resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                windowFocused = windowInfo.isWindowFocused,
                attached = attached,
                visibleBounds = attached && !placed!!.boundsInWindow(clipBounds = true).isEmpty,
                intentCurrent = intentCurrent(),
            )
        },
    )
}

private fun Context.faceUiActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return current as? Activity
}

@Composable
internal fun FacePageActions(state: AppUiState, actions: AppActions, host: FaceActionHost, onPickHwt: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FacePageActionButton(
                Tr.tr("选择 HWT", "Seleziona HWT", "Select HWT", "Seleccionar HWT", "Choisir un HWT", "Selecionar HWT", "Selecionar HWT", "Избор на HWT", "HWT seç", "HWT auswählen"), FacePageAction.CHOOSE_HWT,
                state, actions, host, onPickHwt, Modifier.weight(1f),
            )
            FacePageActionButton(
                Tr.tr("清除导入缓存", "Svuota cache di importazione", "Clear import cache", "Borrar caché de importación", "Vider le cache d'importation", "Limpar cache de importação", "Limpar cache de importação", "Изчистване на кеша за импортиране", "İçe aktarma önbelleğini temizle", "Import-Cache leeren"), FacePageAction.CLEAR_IMPORT_CACHE,
                state, actions, host, onPickHwt, Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FacePageActionButton(
                if (state.listRefreshing) Tr.tr("正在刷新", "Aggiornamento…", "Refreshing…", "Actualizando…", "Actualisation…", "A atualizar…", "Atualizando…", "Опресняване…", "Yenileniyor…", "Aktualisieren…") else Tr.tr("刷新列表", "Aggiorna elenco", "Refresh list", "Actualizar lista", "Actualiser la liste", "Atualizar lista", "Atualizar lista", "Опресняване на списъка", "Listeyi yenile", "Liste aktualisieren"), FacePageAction.REFRESH,
                state, actions, host, onPickHwt, Modifier.weight(1f),
            )
            FacePageActionButton(
                Tr.tr("尝试直接安装", "Prova installazione diretta", "Try direct install", "Probar instalación directa", "Essayer l'installation directe", "Experimentar instalação direta", "Testar instalação direta", "Опит за директно инсталиране", "Doğrudan yüklemeyi dene", "Direktinstallation versuchen"), FacePageAction.DIRECT_INSTALL,
                state, actions, host, onPickHwt, Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FacePageActionButton(
    text: String,
    action: FacePageAction,
    state: AppUiState,
    actions: AppActions,
    host: FaceActionHost,
    onPickHwt: () -> Unit,
    modifier: Modifier,
) {
    val frame = FaceCardPolicy.capturePageAction(state, action)
    val interaction = rememberFaceUiInteraction(
        frame, host, actions,
        intentCurrent = { action != FacePageAction.CHOOSE_HWT || host.pickHwt() === onPickHwt },
    )
    ActionButton(
        text = text,
        modifier = modifier.then(interaction.modifier).semantics {
            frame.disabledReason?.let { stateDescription = it.message }
        },
        enabled = frame.enabled,
        onClick = {
            FaceCardPolicy.invokePageEnabled(frame, host.state(), actions, host.actions(), interaction.guard(), onPickHwt)
        },
        disabledHint = {
            FaceCardPolicy.invokePageDisabledHint(frame, host.state(), actions, host.actions(), interaction.guard()) { message ->
                Toast.makeText(interaction.context, message, Toast.LENGTH_SHORT).show()
            }
        },
    )
}

/** 三枚同权重胶囊始终存在；真实 Button 的 enabled / 无障碍 disabled 语义不被提示层放宽。 */
@Composable
internal fun FaceCardActions(
    face: FaceItem,
    epoch: Long,
    state: AppUiState,
    actions: AppActions,
    host: FaceActionHost,
    ownerCurrent: () -> Boolean,
    expandedIntentCurrent: () -> Boolean,
) {
    val iconAlpha = faceCardIconAlpha(state.theme, isSystemInDarkTheme())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (action in FaceCardAction.entries) {
            FaceActionCapsule(
                FaceCardPolicy.capture(state, face, epoch, action), actions, host,
                ownerCurrent, expandedIntentCurrent, iconAlpha, Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FaceActionCapsule(
    frame: FaceCardActionFrame,
    actions: AppActions,
    host: FaceActionHost,
    ownerCurrent: () -> Boolean,
    expandedIntentCurrent: () -> Boolean,
    iconAlpha: Float,
    modifier: Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val container = scheme.secondaryContainer.copy(alpha = 0.8f)
    val iconTint = scheme.onSurface.copy(alpha = iconAlpha)
    val interaction = rememberFaceUiInteraction(frame, host, actions, ownerCurrent, expandedIntentCurrent)
    val disabledHint: () -> Unit = {
        FaceCardPolicy.invokeDisabledHint(frame, host.state(), actions, host.actions(), interaction.guard()) { message ->
            Toast.makeText(interaction.context, message, Toast.LENGTH_SHORT).show()
        }
    }
    val hintModifier = if (!frame.enabled) {
        disabledButtonHintModifier(disabledHint, DisabledButtonHintShape.CAPSULE)
    } else Modifier
    val icon = ImageVector.vectorResource(when (frame.action) {
        FaceCardAction.REPLACE -> R.drawable.ic_round_autorenew
        FaceCardAction.ENABLE -> R.drawable.ic_round_check_circle_outline
        FaceCardAction.DELETE -> R.drawable.ic_clean
    })
    Button(
        onClick = {
            FaceCardPolicy.invokeEnabled(frame, host.state(), actions, host.actions(), interaction.guard())
        },
        modifier = modifier
            .then(interaction.modifier)
            .alpha(if (frame.enabled) 1f else 0.38f)
            .clip(CircleShape)
            .background(container)
            .then(hintModifier)
            .semantics { frame.disabledReason?.let { stateDescription = it.message } },
        enabled = frame.enabled,
        // Public 0.9.3 Button supplies behavior/semantics; the outer CircleShape is the exact capsule.
        // A transparent, square inner surface cannot shrink that silhouette into a squircle.
        cornerRadius = 0.dp,
        minHeight = 35.dp,
        minWidth = 35.dp,
        colors = ButtonDefaults.buttonColors(
            color = Color.Transparent, disabledColor = Color.Transparent,
            contentColor = iconTint, disabledContentColor = iconTint,
        ),
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = iconTint)
        Spacer(Modifier.width(4.dp))
        Text(
            frame.action.label, color = iconTint,
            fontWeight = FontWeight.Medium, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
