package io.github.escosis.hwtfaceswap.ui

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
        title = "Il watch richiede il codice di abbinamento",
        summary = state.pinMessage.uiText(),
        onDismissRequest = ::cancel,
    ) {
        Column {
            TextField(
                value = pin,
                onValueChange = { value ->
                    if (value.length <= 16 && value.all { it in '0'..'9' }) pin = value
                },
                label = "Inserisci il PIN mostrato sul watch",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !submitted,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(Modifier.height(12.dp))
            MutedText(if (submitted) "Codice di abbinamento inviato, in attesa di risposta dal watch." else "Inserisci il codice di abbinamento mostrato sul watch.")
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Annulla connessione", ::cancel, Modifier.weight(1f))
                ActionButton("Invia codice", ::submit, Modifier.weight(1f), primary = true, enabled = pin.isNotBlank() && !submitted)
            }
        }
    }
}
