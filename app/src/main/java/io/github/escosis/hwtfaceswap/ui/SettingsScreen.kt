package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import io.github.escosis.hwtfaceswap.R
import io.github.escosis.hwtfaceswap.ThemeMode
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsScreen(
    state: AppUiState,
    actions: AppActions,
    insets: PaddingValues,
    onNavigateToAbout: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onOverlayExpandedChange: (Boolean) -> Unit = {},
    scrollModifier: Modifier = Modifier,
) {
    var themeExpanded by remember { mutableStateOf(false) }
    val latestState by rememberUpdatedState(state)
    val latestActions by rememberUpdatedState(actions)
    val owner = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val currentOnOverlayExpandedChange by rememberUpdatedState(onOverlayExpandedChange)
    fun normalSettingsAllowed(): Boolean = owner.get() && !latestState.introductionPending
    SideEffect { currentOnOverlayExpandedChange(themeExpanded && !state.introductionPending) }
    LaunchedEffect(state.introductionPending) { if(state.introductionPending) themeExpanded = false }
    DisposableEffect(owner) { onDispose { owner.set(false); currentOnOverlayExpandedChange(false) } }

    LazyColumn(
        modifier = scrollModifier.fillMaxSize().consumeWindowInsets(insets),
        contentPadding = pageContentPadding(insets),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "app_config") {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = Tr.tr("启动自动连接", "Connessione automatica all'avvio", "Auto-connect on launch", "Conexión automática al iniciar", "Connexion automatique au démarrage", "Ligação automática ao iniciar", "Conexão automática ao iniciar", "Автоматично свързване при стартиране", "Açılışta otomatik bağlan", "Automatisch verbinden beim Start", "Автоподключение при запуске"),
                    summary = Tr.tr("应用启动时连接上次成功连接的手表", "All'avvio dell'app connetti l'ultimo watch connesso correttamente", "When the app starts, connect to the last successfully connected watch", "Al abrir la app, conectar con el último reloj conectado correctamente", "Au démarrage de l'app, se connecter à la dernière montre connectée avec succès", "Ao abrir a app, ligar ao último relógio ligado com êxito", "Ao abrir o app, conectar ao último relógio conectado com sucesso", "При стартиране на приложението да се свързва с последния успешно свързан часовник", "Uygulama açıldığında son başarıyla bağlanan saate bağlan", "Beim App-Start mit der zuletzt erfolgreich verbundenen Uhr verbinden", "При запуске приложения подключаться к последним успешно подключённым часам"),
                    checked = state.autoConnect,
                    enabled = !state.introductionPending,
                    onCheckedChange = { if(normalSettingsAllowed()) latestActions.setAutoConnect(it) },
                    startAction = { PreferenceIcon(R.drawable.ic_link) },
                )
            }
        }
        item(key = "theme") {
            Card(Modifier.fillMaxWidth()) {
                if(state.introductionPending) {
                    // 直接移除普通 popup owner，避免仅 enabled=false 仍保留库内部已展开状态。
                    BasicComponent(title = Tr.tr("主题", "Tema", "Theme", "Tema", "Thème", "Tema", "Tema", "Тема", "Tema", "Design", "Тема"), summary = Tr.tr("选择应用外观", "Scegli l'aspetto dell'app", "Choose the app appearance", "Elige el aspecto de la app", "Choisissez l'apparence de l'app", "Escolha o aspeto da app", "Escolha a aparência do app", "Изберете облика на приложението", "Uygulamanın görünümünü seçin", "Wähle das Aussehen der App", "Выберите внешний вид приложения"), enabled = false,
                        startAction = { PreferenceIcon(R.drawable.ic_palette, Tr.tr("主题", "Tema", "Theme", "Tema", "Thème", "Tema", "Tema", "Тема", "Tema", "Design", "Тема")) })
                } else OverlayDropdownPreference(
                    title = Tr.tr("主题", "Tema", "Theme", "Tema", "Thème", "Tema", "Tema", "Тема", "Tema", "Design", "Тема"),
                    summary = Tr.tr("选择应用外观", "Scegli l'aspetto dell'app", "Choose the app appearance", "Elige el aspecto de la app", "Choisissez l'apparence de l'app", "Escolha o aspeto da app", "Escolha a aparência do app", "Изберете облика на приложението", "Uygulamanın görünümünü seçin", "Wähle das Aussehen der App", "Выберите внешний вид приложения"),
                    items = listOf(Tr.tr("跟随系统", "Segui il sistema", "Follow system", "Según el sistema", "Selon le système", "Igual ao sistema", "Seguir o sistema", "Според системата", "Sistemi izle", "Wie System", "Как в системе"), Tr.tr("浅色", "Chiaro", "Light", "Claro", "Clair", "Claro", "Claro", "Светла", "Açık", "Hell", "Светлая"), Tr.tr("深色", "Scuro", "Dark", "Oscuro", "Sombre", "Escuro", "Escuro", "Тъмна", "Koyu", "Dunkel", "Тёмная")),
                    startAction = { PreferenceIcon(R.drawable.ic_palette, Tr.tr("主题", "Tema", "Theme", "Tema", "Thème", "Tema", "Tema", "Тема", "Tema", "Design", "Тема")) },
                    selectedIndex = state.theme.ordinal,
                    onExpandedChange = { themeExpanded = it && normalSettingsAllowed() },
                    onSelectedIndexChange = { if(normalSettingsAllowed()) latestActions.setTheme(ThemeMode.entries[it]) },
                )
            }
        }
        item(key = "update") {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = Tr.tr("自动检查更新", "Controllo automatico aggiornamenti", "Automatic update check", "Comprobación automática de actualizaciones", "Vérification automatique des mises à jour", "Verificação automática de atualizações", "Verificação automática de atualizações", "Автоматична проверка за актуализации", "Otomatik güncelleme denetimi", "Automatische Update-Prüfung", "Автоматическая проверка обновлений"),
                        summary = Tr.tr("应用启动时自动检查新版本", "Controlla nuove versioni all'avvio dell'app", "Check for new versions when the app starts", "Buscar nuevas versiones al abrir la app", "Rechercher les nouvelles versions au démarrage de l'app", "Procurar novas versões ao abrir a app", "Procurar novas versões ao abrir o app", "Търсене на нови версии при стартиране на приложението", "Uygulama açıldığında yeni sürümleri denetle", "Beim App-Start nach neuen Versionen suchen", "Проверять новые версии при запуске приложения"),
                        checked = state.autoCheckUpdate,
                        enabled = !state.introductionPending,
                        onCheckedChange = { if(normalSettingsAllowed()) latestActions.setAutoCheckUpdate(it) },
                        startAction = { PreferenceIcon(R.drawable.ic_round_autorenew) },
                    )
                    ArrowPreference(
                        title = Tr.tr("检查更新", "Controlla aggiornamenti", "Check for updates", "Buscar actualizaciones", "Rechercher des mises à jour", "Procurar atualizações", "Procurar atualizações", "Проверка за актуализации", "Güncellemeleri denetle", "Nach Updates suchen", "Проверить обновления"),
                        summary = state.updateMessage.ifBlank { Tr.tr("当前版本 ${state.appVersion}", "Versione attuale ${state.appVersion}", "Current version ${state.appVersion}", "Versión actual ${state.appVersion}", "Version actuelle ${state.appVersion}", "Versão atual ${state.appVersion}", "Versão atual ${state.appVersion}", "Текуща версия ${state.appVersion}", "Geçerli sürüm ${state.appVersion}", "Aktuelle Version ${state.appVersion}", "Текущая версия ${state.appVersion}") },
                        startAction = { PreferenceIcon(R.drawable.ic_round_system_update_alt) },
                        enabled = !state.introductionPending && !state.checkingUpdate,
                        onClick = { if(normalSettingsAllowed() && !latestState.checkingUpdate) latestActions.checkUpdates() },
                    )
                }
            }
        }
        item(key = "logs_entry") {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = Tr.tr("运行日志", "Log di esecuzione", "Run log", "Registro de ejecución", "Journal d'exécution", "Registo de execução", "Registro de execução", "Дневник на изпълнението", "Çalışma günlüğü", "Laufprotokoll", "Журнал работы"),
                    startAction = { PreferenceIcon(R.drawable.ic_logs, Tr.tr("运行日志", "Log di esecuzione", "Run log", "Registro de ejecución", "Journal d'exécution", "Registo de execução", "Registro de execução", "Дневник на изпълнението", "Çalışma günlüğü", "Laufprotokoll", "Журнал работы")) },
                    enabled = !state.introductionPending,
                    onClick = { if(normalSettingsAllowed()) onNavigateToLogs() },
                )
            }
        }
        item(key = "about_entry") {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = Tr.tr("关于", "Informazioni", "About", "Información", "À propos", "Acerca de", "Sobre", "Информация", "Hakkında", "Über", "О приложении"),
                    startAction = { PreferenceIcon(R.drawable.ic_round_info, Tr.tr("关于", "Informazioni", "About", "Información", "À propos", "Acerca de", "Sobre", "Информация", "Hakkında", "Über", "О приложении")) },
                    enabled = !state.introductionPending,
                    onClick = { if(normalSettingsAllowed()) onNavigateToAbout() },
                )
            }
        }
    }
}

@Composable
internal fun PreferenceIcon(iconRes: Int, contentDescription: String? = null) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = contentDescription,
        modifier = Modifier.padding(end = 6.dp).size(24.dp),
        tint = MiuixTheme.colorScheme.onSurface,
    )
}
