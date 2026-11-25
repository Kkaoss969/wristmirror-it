package io.github.escosis.hwtfaceswap.ui


import io.github.escosis.hwtfaceswap.Tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.escosis.hwtfaceswap.AppActions
import io.github.escosis.hwtfaceswap.AppUiState
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun RuntimeDialogs(state: AppUiState, actions: AppActions) {
    // 一次性操作令牌只留在内存中，不进入界面恢复数据。
    var consumedPromptToken by remember { mutableStateOf<Long?>(null) }
    val prompt = state.prompt
    if (prompt != null) {
        fun consume(confirm: Boolean) {
            if (consumedPromptToken == prompt.token) return
            consumedPromptToken = prompt.token
            if (confirm) actions.confirmAction(prompt.token) else actions.dismissAction(prompt.token)
        }
        WindowDialog(
            show = !state.needsPin && consumedPromptToken != prompt.token,
            title = prompt.title.uiText(),
            summary = prompt.message.uiText(),
            onDismissRequest = { consume(false) },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(prompt.negativeLabel, { consume(false) }, Modifier.weight(1f))
                ActionButton(
                    text = prompt.positiveLabel,
                    onClick = { consume(true) },
                    modifier = Modifier.weight(1f),
                    primary = !prompt.destructive,
                    destructive = prompt.destructive,
                )
            }
        }
    }
    WatchRequestedPinDialog(state, actions)
    ReplacementProgressDialog(state, actions)
}

@Composable
private fun WatchRequestedPinDialog(state: AppUiState, actions: AppActions) {
    if (!state.needsPin) return
    val requestedEpoch = state.connectionEpoch
    var pin by remember(requestedEpoch, state.pinMessage) { mutableStateOf("") }
    var submitted by remember(requestedEpoch, state.pinMessage) { mutableStateOf(false) }
    var cancelled by remember(requestedEpoch, state.pinMessage) { mutableStateOf(false) }
    fun cancel() {
        if (cancelled) return
        cancelled = true
        pin = ""
        actions.cancelPin(requestedEpoch)
    }
    fun submit() {
        if (submitted || cancelled || pin.isBlank()) return
        submitted = true
        val entered = pin
        pin = ""
        actions.submitPin(requestedEpoch, entered)
    }
    WindowDialog(
        show = !cancelled,
        title = Tr.tr("手表请求配对码", "Il watch richiede il codice di abbinamento", "The watch requests a pairing code", "El reloj pide un código de emparejamiento", "La montre demande un code d'association", "O relógio pede um código de emparelhamento", "O relógio pede um código de pareamento", "Часовникът иска код за сдвояване", "Saat eşleştirme kodu istiyor", "Die Uhr fordert einen Kopplungscode an"),
        summary = state.pinMessage.uiText(),
        onDismissRequest = ::cancel,
    ) {
        Column {
            TextField(
                value = pin,
                onValueChange = { value ->
                    if (value.length <= 16 && value.all { it in '0'..'9' }) pin = value
                },
                label = Tr.tr("输入手表显示的 PIN", "Inserisci il PIN mostrato sul watch", "Enter the PIN shown on the watch", "Introduce el PIN que muestra el reloj", "Saisissez le PIN affiché sur la montre", "Introduza o PIN mostrado no relógio", "Insira o PIN mostrado no relógio", "Въведете ПИН кода, показан на часовника", "Saatte görünen PIN'i girin", "Gib die auf der Uhr angezeigte PIN ein"),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !submitted,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.height(12.dp))
            MutedText(if (submitted) Tr.tr("配对码已提交，等待手表响应。", "Codice di abbinamento inviato, in attesa di risposta dal watch.", "Pairing code sent, waiting for the watch to respond.", "Código enviado, esperando la respuesta del reloj.", "Code envoyé, en attente de la réponse de la montre.", "Código enviado, a aguardar a resposta do relógio.", "Código enviado, aguardando a resposta do relógio.", "Кодът е изпратен, изчакване на отговор от часовника.", "Kod gönderildi, saatin yanıtı bekleniyor.", "Code gesendet, warten auf die Antwort der Uhr.") else Tr.tr("请输入手表上显示的配对码。", "Inserisci il codice di abbinamento mostrato sul watch.", "Enter the pairing code shown on the watch.", "Introduce el código de emparejamiento que muestra el reloj.", "Saisissez le code d'association affiché sur la montre.", "Introduza o código de emparelhamento mostrado no relógio.", "Insira o código de pareamento mostrado no relógio.", "Въведете кода за сдвояване, показан на часовника.", "Saatte görünen eşleştirme kodunu girin.", "Gib den auf der Uhr angezeigten Kopplungscode ein."))
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(Tr.tr("取消连接", "Annulla connessione", "Cancel connection", "Cancelar conexión", "Annuler la connexion", "Cancelar ligação", "Cancelar conexão", "Отмяна на свързването", "Bağlanmayı iptal et", "Verbinden abbrechen"), ::cancel, Modifier.weight(1f))
                ActionButton(Tr.tr("提交配对码", "Invia codice", "Send code", "Enviar código", "Envoyer le code", "Enviar código", "Enviar código", "Изпращане на кода", "Kodu gönder", "Code senden"), ::submit, Modifier.weight(1f), primary = true, enabled = pin.isNotBlank() && !submitted)
            }
        }
    }
}
