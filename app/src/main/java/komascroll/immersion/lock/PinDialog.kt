package komascroll.immersion.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import komascroll.immersion.PinHasher
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Dialog with one masked PIN field per label. [onConfirm] gets the entered values and returns an
 * error to show, or null when done (the dialog then closes).
 */
@Composable
fun PinDialog(
    title: String,
    labels: List<String>,
    onDismiss: () -> Unit,
    onConfirm: suspend (List<String>) -> String?,
    message: String? = null,
) {
    val scope = rememberCoroutineScope()
    val values = remember { mutableStateListOf(*Array(labels.size) { "" }) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (message != null) {
                    Text(message)
                    Spacer(Modifier.height(12.dp))
                }
                labels.forEachIndexed { index, label ->
                    OutlinedTextField(
                        value = values[index],
                        onValueChange = { input ->
                            values[index] = input.filter(Char::isDigit).take(PinHasher.MAX_LENGTH)
                            error = null
                        },
                        label = { Text(label) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && values.all { it.isNotEmpty() },
                onClick = {
                    busy = true
                    scope.launch {
                        val result = onConfirm(values.toList())
                        busy = false
                        if (result == null) onDismiss() else error = result
                    }
                },
            ) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
