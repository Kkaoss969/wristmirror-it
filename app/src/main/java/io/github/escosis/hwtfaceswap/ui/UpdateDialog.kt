package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
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
                Tr.tr("发现新版本", "Nuova versione disponibile", "New version available", "Nueva versión disponible", "Nouvelle version disponible", "Nova versão disponível", "Nova versão disponível", "Налична е нова версия", "Yeni sürüm mevcut", "Neue Version verfügbar"),
                modifier = Modifier.semantics { heading() },
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Tr.tr("当前版本：$currentVersion", "Versione attuale: $currentVersion", "Current version: $currentVersion", "Versión actual: $currentVersion", "Version actuelle : $currentVersion", "Versão atual: $currentVersion", "Versão atual: $currentVersion", "Текуща версия: $currentVersion", "Geçerli sürüm: $currentVersion", "Aktuelle Version: $currentVersion"), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
                Text(Tr.tr("最新版本：${info.version}", "Ultima versione: ${info.version}", "Latest version: ${info.version}", "Última versión: ${info.version}", "Dernière version : ${info.version}", "Última versão: ${info.version}", "Última versão: ${info.version}", "Най-нова версия: ${info.version}", "En son sürüm: ${info.version}", "Neueste Version: ${info.version}"), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
            }
            Text(Tr.tr("前往发布页查看更新并下载 APK。", "Vai alla pagina della release per vedere le novità e scaricare l'APK.", "Go to the release page to see what's new and download the APK.", "Ve a la página de la versión para ver las novedades y descargar el APK.", "Allez sur la page de la version pour voir les nouveautés et télécharger l'APK.", "Vá à página da versão para ver as novidades e transferir o APK.", "Vá à página da versão para ver as novidades e baixar o APK.", "Отидете на страницата на версията, за да видите новото и да изтеглите APK.", "Yenilikleri görmek ve APK'yı indirmek için sürüm sayfasına gidin.", "Geh zur Versionsseite, um die Neuerungen zu sehen und die APK herunterzuladen."), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
            // 退出期间保留按钮外观；重复点击由同步 owner/request 守卫拒绝。
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(Tr.tr("稍后", "Più tardi", "Later", "Más tarde", "Plus tard", "Mais tarde", "Mais tarde", "По-късно", "Daha sonra", "Später"), { requestDismissal(UpdateDialogAction.DISMISS) }, Modifier.weight(1f))
                ActionButton(Tr.tr("打开发布页", "Apri pagina della release", "Open release page", "Abrir página de la versión", "Ouvrir la page de la version", "Abrir página da versão", "Abrir página da versão", "Отваряне на страницата на версията", "Sürüm sayfasını aç", "Versionsseite öffnen"), { requestDismissal(UpdateDialogAction.OPEN_RELEASE) }, Modifier.weight(1f), primary = true)
            }
        }
    }
}
