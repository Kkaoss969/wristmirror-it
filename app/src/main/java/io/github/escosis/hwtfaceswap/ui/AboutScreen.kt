package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.escosis.hwtfaceswap.R
import io.github.escosis.hwtfaceswap.UpdateChecker
import top.yukonga.miuix.kmp.basic.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    val githubUrl = UpdateChecker.REPOSITORY_URL

    CollapsingPage(
        title = Tr.tr("关于", "Informazioni", "About", "Información", "À propos", "Acerca de", "Sobre", "Информация", "Hakkında", "Über"),
        fullscreen = true,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard_backspace),
                    contentDescription = Tr.tr("返回", "Indietro", "Back", "Atrás", "Retour", "Voltar", "Voltar", "Назад", "Geri", "Zurück"),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        },
    ) { padding, scrollModifier ->
        Column(
            modifier = scrollModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .consumeWindowInsets(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = Tr.tr("腕镜应用图标", "Icona di WristMirror", "WristMirror app icon", "Icono de WristMirror", "Icône de WristMirror", "Ícone do WristMirror", "Ícone do WristMirror", "Икона на WristMirror", "WristMirror simgesi", "WristMirror-Symbol"),
                modifier = Modifier.size(128.dp),
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = Tr.tr("腕镜", "WristMirror", "WristMirror", "WristMirror", "WristMirror", "WristMirror", "WristMirror", "WristMirror", "WristMirror", "WristMirror"),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = versionName?.let { "v$it" } ?: Tr.tr("版本未知", "Versione sconosciuta", "Version unknown", "Versión desconocida", "Version inconnue", "Versão desconhecida", "Versão desconhecida", "Неизвестна версия", "Sürüm bilinmiyor", "Version unbekannt"),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = Tr.tr("一个华为手表表盘管理和第三方 HWT 表盘安装工具，面向绝大多数采用兼容表盘协议的 WATCH、GT、FIT 等系列设备，第三方表盘安装通过替换法支持直装法失灵的新鸿蒙版本。支持连接、读取、启用和删除手表中表盘，本地 HWT 解析、直装或替换表盘；具体兼容性以设备型号和固件实现为准。华为 ID 与认证资料按设备加密保存在本机，更新检查通过 GitHub 完成。", "Strumento per gestire i quadranti degli smartwatch Huawei e installare quadranti HWT di terze parti, pensato per la maggior parte dei dispositivi delle serie WATCH, GT, FIT ecc. che usano il protocollo quadranti compatibile; per le versioni HarmonyOS più recenti, dove l'installazione diretta non funziona, i quadranti di terze parti si installano col metodo della sostituzione. Permette di connettersi, leggere, attivare ed eliminare i quadranti sul watch, analizzare HWT locali e installarli direttamente o in sostituzione; la compatibilità effettiva dipende dal modello e dal firmware. ID Huawei e credenziali di autenticazione sono salvati cifrati sul telefono per ciascun dispositivo; il controllo aggiornamenti avviene tramite GitHub. Autore: Escosis (github.com/Escosis/WristMirror, licenza Apache-2.0). Questa è una traduzione non ufficiale, distribuita da Kkaoss969 con Gateway Assistant.", "A tool to manage Huawei watch faces and install third-party HWT watch faces, for most WATCH, GT, FIT etc. series devices using the compatible watch face protocol; on newer HarmonyOS versions where direct install fails, third-party watch faces are installed by replacement. It can connect, read, activate and delete watch faces on the watch, parse local HWT files and install them directly or by replacement; actual compatibility depends on the model and firmware. Huawei ID and authentication credentials are stored encrypted on the phone per device; update checks go through GitHub. Author: Escosis (github.com/Escosis/WristMirror, Apache-2.0 license). This is an unofficial translation, distributed by Kkaoss969 with Gateway Assistant.", "Herramienta para gestionar esferas de relojes Huawei e instalar esferas HWT de terceros, para la mayoría de dispositivos de las series WATCH, GT, FIT, etc. con el protocolo de esferas compatible; en las versiones recientes de HarmonyOS donde la instalación directa falla, las esferas de terceros se instalan por sustitución. Permite conectarse, leer, activar y eliminar esferas del reloj, analizar HWT locales e instalarlos directamente o por sustitución; la compatibilidad real depende del modelo y del firmware. El ID de Huawei y las credenciales se guardan cifrados en el teléfono para cada dispositivo; las actualizaciones se comprueban en GitHub. Autor: Escosis (github.com/Escosis/WristMirror, licencia Apache-2.0). Esta es una traducción no oficial, distribuida por Kkaoss969 con Gateway Assistant.", "Outil pour gérer les cadrans des montres Huawei et installer des cadrans HWT tiers, pour la plupart des appareils des séries WATCH, GT, FIT, etc. utilisant le protocole de cadrans compatible ; sur les versions récentes de HarmonyOS où l'installation directe échoue, les cadrans tiers s'installent par remplacement. Il permet de se connecter, lire, activer et supprimer les cadrans de la montre, analyser des HWT locaux et les installer directement ou par remplacement ; la compatibilité réelle dépend du modèle et du firmware. L'ID Huawei et les identifiants sont enregistrés chiffrés sur le téléphone pour chaque appareil ; les mises à jour sont vérifiées sur GitHub. Auteur : Escosis (github.com/Escosis/WristMirror, licence Apache-2.0). Ceci est une traduction non officielle, distribuée par Kkaoss969 avec Gateway Assistant.", "Ferramenta para gerir mostradores de relógios Huawei e instalar mostradores HWT de terceiros, para a maioria dos dispositivos das séries WATCH, GT, FIT, etc. com o protocolo de mostradores compatível; nas versões recentes do HarmonyOS em que a instalação direta falha, os mostradores de terceiros instalam-se por substituição. Permite ligar, ler, ativar e eliminar mostradores do relógio, analisar HWT locais e instalá-los diretamente ou por substituição; a compatibilidade real depende do modelo e do firmware. O ID Huawei e as credenciais são guardados cifrados no telemóvel para cada dispositivo; as atualizações verificam-se no GitHub. Autor: Escosis (github.com/Escosis/WristMirror, licença Apache-2.0). Esta é uma tradução não oficial, distribuída por Kkaoss969 com o Gateway Assistant.", "Ferramenta para gerenciar mostradores de relógios Huawei e instalar mostradores HWT de terceiros, para a maioria dos dispositivos das séries WATCH, GT, FIT etc. com o protocolo de mostradores compatível; nas versões recentes do HarmonyOS em que a instalação direta falha, os mostradores de terceiros são instalados por substituição. Permite conectar, ler, ativar e excluir mostradores do relógio, analisar HWT locais e instalá-los diretamente ou por substituição; a compatibilidade real depende do modelo e do firmware. O ID Huawei e as credenciais ficam salvos criptografados no celular para cada dispositivo; as atualizações são verificadas no GitHub. Autor: Escosis (github.com/Escosis/WristMirror, licença Apache-2.0). Esta é uma tradução não oficial, distribuída por Kkaoss969 com o Gateway Assistant.", "Инструмент за управление на циферблати на часовници Huawei и инсталиране на HWT циферблати от трети страни, за повечето устройства от сериите WATCH, GT, FIT и др. със съвместимия протокол за циферблати; на по-новите версии на HarmonyOS, където директното инсталиране не работи, циферблатите от трети страни се инсталират чрез замяна. Позволява свързване, четене, активиране и изтриване на циферблати на часовника, анализ на локални HWT и инсталирането им директно или чрез замяна; реалната съвместимост зависи от модела и фърмуера. Huawei ID и данните за удостоверяване се пазят шифровани на телефона за всяко устройство; актуализациите се проверяват в GitHub. Автор: Escosis (github.com/Escosis/WristMirror, лиценз Apache-2.0). Това е неофициален превод, разпространяван от Kkaoss969 с Gateway Assistant.", "Huawei saat kadranlarını yönetmek ve üçüncü taraf HWT kadranları yüklemek için bir araç; uyumlu kadran protokolünü kullanan WATCH, GT, FIT vb. serilerin çoğu cihaz için. Doğrudan yüklemenin çalışmadığı yeni HarmonyOS sürümlerinde üçüncü taraf kadranlar değiştirme yoluyla yüklenir. Saatteki kadranlara bağlanma, okuma, etkinleştirme ve silme, yerel HWT'leri çözümleme ve doğrudan veya değiştirme yoluyla yükleme sağlar; gerçek uyumluluk modele ve yazılıma bağlıdır. Huawei ID ve kimlik doğrulama bilgileri her cihaz için telefonda şifreli saklanır; güncellemeler GitHub üzerinden denetlenir. Geliştirici: Escosis (github.com/Escosis/WristMirror, Apache-2.0 lisansı). Bu, Kkaoss969 tarafından Gateway Assistant ile dağıtılan resmî olmayan bir çeviridir.", "Ein Werkzeug zum Verwalten von Huawei-Zifferblättern und zum Installieren von HWT-Zifferblättern von Drittanbietern, für die meisten Geräte der Serien WATCH, GT, FIT usw. mit dem kompatiblen Zifferblattprotokoll; auf neueren HarmonyOS-Versionen, auf denen die Direktinstallation nicht funktioniert, werden Zifferblätter von Drittanbietern durch Ersetzen installiert. Es kann sich verbinden, Zifferblätter auf der Uhr lesen, aktivieren und löschen, lokale HWT-Dateien analysieren und direkt oder durch Ersetzen installieren; die tatsächliche Kompatibilität hängt von Modell und Firmware ab. Huawei-ID und Anmeldedaten werden pro Gerät verschlüsselt auf dem Handy gespeichert; Updates werden über GitHub geprüft. Autor: Escosis (github.com/Escosis/WristMirror, Apache-2.0-Lizenz). Dies ist eine inoffizielle Übersetzung, verteilt von Kkaoss969 mit Gateway Assistant."),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )

            Spacer(Modifier.height(32.dp))

            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
            ) {
                ArrowPreference(
                    title = Tr.tr("项目仓库", "Repository del progetto", "Project repository", "Repositorio del proyecto", "Dépôt du projet", "Repositório do projeto", "Repositório do projeto", "Хранилище на проекта", "Proje deposu", "Projekt-Repository"),
                    summary = "Escosis/WristMirror",
                    startAction = {
                        Icon(
                            painter = painterResource(R.drawable.ic_github_mono),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp).size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                        } catch (_: Exception) {
                            Toast.makeText(context, Tr.tr("打开链接失败", "Impossibile aprire il link", "Couldn't open the link", "No se pudo abrir el enlace", "Impossible d'ouvrir le lien", "Não foi possível abrir a ligação", "Não foi possível abrir o link", "Връзката не може да се отвори", "Bağlantı açılamadı", "Link konnte nicht geöffnet werden"), Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
