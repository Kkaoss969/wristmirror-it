package io.github.escosis.hwtfaceswap.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.escosis.hwtfaceswap.UpdateInfo
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class UpdateDialogAction { DISMISS, OPEN_RELEASE }

/** 复用首次说明的居中窗口；退出并释放真实窗口后再通知父界面。 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
    onOpenReleasePage: () -> Unit,
    isCurrent: () -> Boolean = { true },
) {
    val owner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(owner) { onDispose { owner.set(false) } }
    val latestIsCurrent by rememberUpdatedState(isCurrent)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestOnOpenReleasePage by rememberUpdatedState(onOpenReleasePage)
    val latestContext by rememberUpdatedState(LocalContext.current)
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestLifecycleOwner by rememberUpdatedState(lifecycleOwner)
    var requestedAction by remember { mutableStateOf<UpdateDialogAction?>(null) }
    var animationFinished by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    val nativeDisposalReceipt = remember { mutableStateOf(false) }
    val nativeDisposed = nativeDisposalReceipt.value

    DisposableEffect(owner, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            // 帧时钟可能在后台暂停；立即取消旧打开意图，恢复动画后也只关闭提示。
            if (owner.get() && !completed && requestedAction == UpdateDialogAction.OPEN_RELEASE &&
                (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP)) {
                requestedAction = UpdateDialogAction.DISMISS
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun ownerVisible(): Boolean = latestLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        (latestContext as? Activity)?.let { !it.isFinishing && !it.isDestroyed } != false

    fun requestDismissal(action: UpdateDialogAction) {
        if (!owner.get() || requestedAction != null || !ownerVisible() || !latestIsCurrent()) return
        requestedAction = action
    }

    LaunchedEffect(owner, requestedAction, animationFinished, nativeDisposed) {
        if (!owner.get() || completed || !animationFinished || !nativeDisposed) return@LaunchedEffect
        val action = requestedAction ?: return@LaunchedEffect
        completed = true
        if (!latestIsCurrent()) return@LaunchedEffect
        when (action) {
            UpdateDialogAction.DISMISS -> latestOnDismiss()
            // 退出动画期间切到后台时只清提示，不从后台打开链接，也不在恢复后补开。
            UpdateDialogAction.OPEN_RELEASE -> if (ownerVisible()) latestOnOpenReleasePage() else latestOnDismiss()
        }
    }

    CenteredIntroductionHost(
        show = requestedAction == null,
        onDismissRequest = { requestDismissal(UpdateDialogAction.DISMISS) },
        onDismissFinished = {
            if (owner.get() && requestedAction != null) animationFinished = true
        },
    ) {
        // 动画完成不代表原生 Dialog 已释放；父 Composition 显式观察真实 disposal 收据。
        DisposableEffect(owner) {
            onDispose {
                if (owner.get() && requestedAction != null && animationFinished) nativeDisposalReceipt.value = true
            }
        }
        NavigationBackHandler(
            state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
            isBackEnabled = true,
            onBackCompleted = { requestDismissal(UpdateDialogAction.DISMISS) },
        )
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Nuova versione disponibile",
                modifier = Modifier.semantics { heading() },
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Versione attuale: $currentVersion", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
                Text("Ultima versione: ${info.version}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
            }
            Text("Vai alla pagina della release per vedere le novità e scaricare l'APK.", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
            // 退出期间保留按钮外观；重复点击由同步 owner/request 守卫拒绝。
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("Più tardi", { requestDismissal(UpdateDialogAction.DISMISS) }, Modifier.weight(1f))
                ActionButton("Apri pagina della release", { requestDismissal(UpdateDialogAction.OPEN_RELEASE) }, Modifier.weight(1f), primary = true)
            }
        }
    }
}
