package io.github.escosis.hwtfaceswap.ui

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
        title = "Informazioni",
        fullscreen = true,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard_backspace),
                    contentDescription = "Indietro",
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
                contentDescription = "Icona di WristMirror",
                modifier = Modifier.size(128.dp),
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "WristMirror",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = versionName?.let { "v$it" } ?: "Versione sconosciuta",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Strumento per gestire i quadranti degli smartwatch Huawei e installare quadranti HWT di terze parti, pensato per la maggior parte dei dispositivi delle serie WATCH, GT, FIT ecc. che usano il protocollo quadranti compatibile; per le versioni HarmonyOS più recenti, dove l'installazione diretta non funziona, i quadranti di terze parti si installano col metodo della sostituzione. Permette di connettersi, leggere, attivare ed eliminare i quadranti sul watch, analizzare HWT locali e installarli direttamente o in sostituzione; la compatibilità effettiva dipende dal modello e dal firmware. ID Huawei e credenziali di autenticazione sono salvati cifrati sul telefono per ciascun dispositivo; il controllo aggiornamenti avviene tramite GitHub.",
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
                    title = "Repository del progetto",
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
                            Toast.makeText(context, "Impossibile aprire il link", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
