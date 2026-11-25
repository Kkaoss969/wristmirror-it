package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.IntroductionContent
import io.github.escosis.hwtfaceswap.IntroductionListItem
import io.github.escosis.hwtfaceswap.IntroductionPolicy
import io.github.escosis.hwtfaceswap.UiPolicy
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 返回真实说明宿主仍占用模态槽（含正常退出动画），使普通 UI/更新继续避让。 */
@Composable
internal fun IntroductionDialog(state: AppUiState, actions: AppActions, onDismissFinished: () -> Unit): Boolean {
    // 在优先级 early-return 之前保存最新值，让被 Native 窗口退休的旧 callback 也看到最新状态。
    val latestState = rememberUpdatedState(state)
    val latestActions = rememberUpdatedState(actions)
    val latestOnDismissFinished = rememberUpdatedState(onDismissFinished)
    if (!UiPolicy.introductionModalAvailable(state)) return false
    return IntroductionWindowOwner(state, latestState, latestActions, latestOnDismissFinished)
}

@Composable
private fun IntroductionWindowOwner(
    state: AppUiState,
    latestState: State<AppUiState>,
    latestActions: State<AppActions>,
    latestOnDismissFinished: State<() -> Unit>,
): Boolean {
    var acknowledgingHere by remember { mutableStateOf(false) }
    var acknowledgedHere by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var libraryFinished by remember { mutableStateOf(false) }
    val nativeDisposalReceipt = remember { mutableStateOf(false) }
    val nativeDisposed = nativeDisposalReceipt.value // 必须参与父 Composition 的 snapshot 观察。
    if (!IntroductionPolicy.retainWindow(state.introductionPending, acknowledgingHere, acknowledgedHere, finished)) return false
    val owner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(owner) { onDispose { owner.set(false) } }
    val context = LocalContext.current
    val latestContext by rememberUpdatedState(context)
    val latestLifecycleOwner by rememberUpdatedState(LocalLifecycleOwner.current)
    fun ownerVisible(): Boolean = latestLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        (latestContext as? Activity)?.let { !it.isFinishing && !it.isDestroyed } != false

    fun canInteract(): Boolean = IntroductionPolicy.canInteract(owner.get(), ownerVisible() && !acknowledgingHere && !acknowledgedHere && !finished, latestState.value) &&
        latestActions.value.introductionInteractionIsCurrent()
    fun acknowledge() {
        if (!canInteract()) return
        // 在 VM 同步提交/父状态更新之前占用本地 owner；重组看到 pending=false 也不能提前销毁窗口。
        acknowledgingHere = true
        try {
            if (latestActions.value.acknowledgeIntroduction()) acknowledgedHere = true
        } finally {
            acknowledgingHere = false
        }
    }
    fun copyMall() {
        if (canInteract()) copyIntroductionMall(latestContext)
    }
    fun finishDismissal() {
        if (libraryFinished || !IntroductionPolicy.canRetireDismissal(owner.get(), acknowledgedHere, finished)) return
        // 宿主 hide 完成 callback 先于 Native Dialog 的下一次 apply/disposal，不能在这里触发权限或连接。
        libraryFinished = true
    }
    LaunchedEffect(owner, libraryFinished, nativeDisposed) {
        if (!IntroductionPolicy.canCompleteAfterDisposal(owner.get(), acknowledgedHere, finished,
                libraryFinished, nativeDisposed, latestState.value)) return@LaunchedEffect
        finished = true
        // 真实 child disposal 的 reactive 收据经过新的 apply 后才登记；PAUSED 时由 Activity 留待 RESUMED 消费。
        latestOnDismissFinished.value()
    }

    CenteredIntroductionHost(
        show = !acknowledgedHere,
        onDismissRequest = {},
        onDismissFinished = ::finishDismissal,
    ) {
        // 位于实际 Dialog 子 Composition；Android 宿主先 dismiss，再 disposeComposition，之后才有此收据。
        DisposableEffect(owner) {
            onDispose {
                if (owner.get() && acknowledgedHere && libraryFinished) nativeDisposalReceipt.value = true
            }
        }
        // 显式“我已了解”才记录已读。内层 Back 消费不触发库的预测返回位移，避免空 dismiss 留下面板偏移。
        NavigationBackHandler(
            state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
            isBackEnabled = !acknowledgedHere,
            onBackCompleted = {},
        )
        // 标题、全文和按钮共用一个有界可滚动区；大字体/横屏时也能滚至按钮，不截断正文。
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                Tr.tr("重要提示", "Avviso importante", "Important notice", "Aviso importante", "Avis important", "Aviso importante", "Aviso importante", "Важно известие", "Önemli bildirim", "Wichtiger Hinweis"),
                modifier = Modifier.semantics { heading() },
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
            )
            IntroductionContent.sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        section.title,
                        modifier = Modifier.semantics { heading() },
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    section.items.forEach { item -> IntroductionBullet(item, ::copyMall) }
                }
            }
            // 退出时保留按钮视觉状态；实际重复/旧点击由同步 owner/latest/Runtime 守卫拒绝。
            ActionButton(Tr.tr("我已了解", "Ho capito", "Got it", "Entendido", "J'ai compris", "Compreendi", "Entendi", "Разбрах", "Anladım", "Verstanden"), ::acknowledge, Modifier.fillMaxWidth(), primary = true)
        }
    }
    return true
}

@Composable
private fun IntroductionBullet(item: IntroductionListItem, onCopyMall: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val text = buildAnnotatedString {
        item.parts.forEach { part ->
            if (part.url == IntroductionContent.VMALL_URL) {
                // 仅复制完整商城网址；点击时重新核对 owner 与当前模态槽，不使用 Url 默认导航。
                withLink(LinkAnnotation.Clickable(
                    tag = "introduction-vmall",
                    styles = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onCopyMall() },
                )) { append(part.text) }
            } else append(part.text)
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("•", modifier = Modifier.padding(end = 8.dp), style = MiuixTheme.textStyles.body2, color = colors.onBackground)
        Text(text, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2, color = colors.onBackground)
    }
}

/** 公开网址无需敏感标记；只有实际写入未抛异常才返回复制成功。 */
internal fun tryCopyIntroductionMallLink(writePlainText: (String, String) -> Unit): Boolean = try {
    writePlainText(Tr.tr("华为商城链接", "Link Huawei Store", "Huawei Store link", "Enlace de Huawei Store", "Lien Huawei Store", "Ligação da Huawei Store", "Link da Huawei Store", "Връзка към Huawei Store", "Huawei Store bağlantısı", "Huawei-Store-Link"), IntroductionContent.VMALL_URL)
    true
} catch (_: Exception) {
    false
}

/** 仅在被守卫的链接点击中调用，不访问网页、不读取 Cookie/ID，也不提交说明已读。 */
private fun copyIntroductionMall(context: Context) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val copied = clipboard != null && tryCopyIntroductionMallLink { label, url ->
            clipboard.setPrimaryClip(ClipData.newPlainText(label, url))
        }
        Toast.makeText(context,
            if (copied) Tr.tr("链接已复制，请在电脑浏览器中打开", "Link copiato, aprilo nel browser di un computer", "Link copied, open it in a computer browser", "Enlace copiado, ábrelo en el navegador de un ordenador", "Lien copié, ouvrez-le dans le navigateur d'un ordinateur", "Ligação copiada, abra-a no navegador de um computador", "Link copiado, abra no navegador de um computador", "Връзката е копирана, отворете я в браузър на компютър", "Bağlantı kopyalandı, bir bilgisayar tarayıcısında açın", "Link kopiert, öffne ihn im Browser eines Computers") else Tr.tr("复制链接失败，请重试", "Copia del link non riuscita, riprova", "Copying the link failed, try again", "Error al copiar el enlace, inténtalo de nuevo", "Échec de la copie du lien, réessayez", "Falha ao copiar a ligação, tente de novo", "Falha ao copiar o link, tente de novo", "Копирането на връзката не бе успешно, опитайте пак", "Bağlantı kopyalanamadı, tekrar deneyin", "Link konnte nicht kopiert werden, versuche es erneut"),
            Toast.LENGTH_SHORT,
        ).show()
    } catch (_: Exception) {
        Toast.makeText(context, Tr.tr("复制链接失败，请重试", "Copia del link non riuscita, riprova", "Copying the link failed, try again", "Error al copiar el enlace, inténtalo de nuevo", "Échec de la copie du lien, réessayez", "Falha ao copiar a ligação, tente de novo", "Falha ao copiar o link, tente de novo", "Копирането на връзката не бе успешно, опитайте пак", "Bağlantı kopyalanamadı, tekrar deneyin", "Link konnte nicht kopiert werden, versuche es erneut"), Toast.LENGTH_SHORT).show()
    }
}
