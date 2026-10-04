package komascroll.presentation.lab

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import komascroll.core.SecretStore
import komascroll.i18n.KSR
import komascroll.lab.LabPreferences
import komascroll.translate.TranslationManager
import komascroll.translate.engine.DeepLTextTranslator
import komascroll.translate.engine.MlKitTextTranslator
import komascroll.translate.engine.PageTranslationEngine
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/** The "Live translation" group of the KomaScroll Lab screen. */
@Composable
internal fun translateGroup(preferences: LabPreferences): Preference.PreferenceGroup {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val secrets = remember { Injekt.get<SecretStore>() }
    val manager = remember { Injekt.get<TranslationManager>() }

    val enabled by preferences.translateEnabled().collectAsState()
    val engine by preferences.translateEngine().collectAsState()

    var hasKey by remember { mutableStateOf(secrets.contains(SecretStore.DEEPL_API_KEY)) }
    var showKeyDialog by remember { mutableStateOf(false) }
    if (showKeyDialog) {
        DeepLKeyDialog(
            hasKey = hasKey,
            onDismiss = { showKeyDialog = false },
            onSave = { key ->
                if (key.isBlank()) secrets.remove(SecretStore.DEEPL_API_KEY) else secrets.put(SecretStore.DEEPL_API_KEY, key.trim())
                hasKey = key.isNotBlank()
                showKeyDialog = false
            },
            onRemove = {
                secrets.remove(SecretStore.DEEPL_API_KEY)
                hasKey = false
                showKeyDialog = false
            },
        )
    }

    var cacheRefresh by remember { mutableIntStateOf(0) }
    var cacheSize by remember { mutableStateOf("") }
    LaunchedEffect(cacheRefresh) {
        cacheSize = Formatter.formatFileSize(context, withIOContext { manager.cacheSize() })
    }

    val sourceLanguages = remember {
        PageTranslationEngine.SOURCE_LANGUAGES.associateWith(::displayName).toImmutableMap()
    }
    val targetLanguages = remember {
        PageTranslationEngine.targetLanguages()
            .associateWith(::displayName)
            .entries
            .sortedBy { it.value }
            .associate { it.toPair() }
            .toImmutableMap()
    }

    return Preference.PreferenceGroup(
        title = stringResource(KSR.strings.translate_group),
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.translateEnabled(),
                title = stringResource(KSR.strings.translate_enable),
                subtitle = stringResource(KSR.strings.translate_enable_summary),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.translateSourceLanguage(),
                entries = sourceLanguages,
                title = stringResource(KSR.strings.translate_source_language),
                enabled = enabled,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.translateTargetLanguage(),
                entries = targetLanguages,
                title = stringResource(KSR.strings.translate_target_language),
                enabled = enabled,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.translateEngine(),
                entries = persistentMapOf(
                    MlKitTextTranslator.ID to stringResource(KSR.strings.translate_engine_mlkit),
                    DeepLTextTranslator.ID to stringResource(KSR.strings.translate_engine_deepl),
                ),
                title = stringResource(KSR.strings.translate_engine),
                enabled = enabled,
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KSR.strings.translate_deepl_key),
                subtitle = stringResource(
                    if (hasKey) KSR.strings.translate_deepl_key_set else KSR.strings.translate_deepl_key_unset,
                ),
                enabled = enabled && engine == DeepLTextTranslator.ID,
                onClick = { showKeyDialog = true },
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.translateModelsOnWifiOnly(),
                title = stringResource(KSR.strings.translate_models_wifi),
                subtitle = stringResource(KSR.strings.translate_models_wifi_summary),
                enabled = enabled,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.translateCacheSizeMb(),
                entries = persistentMapOf(
                    50 to "50 MB",
                    100 to "100 MB",
                    200 to "200 MB",
                    500 to "500 MB",
                    1000 to "1 GB",
                ),
                title = stringResource(KSR.strings.translate_cache_limit),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KSR.strings.translate_cache_clear),
                subtitle = stringResource(KSR.strings.upscale_cache_used, cacheSize),
                onClick = {
                    scope.launch {
                        val deleted = withIOContext { manager.clearCache() }
                        context.toast(context.stringResource(KSR.strings.translate_cache_cleared, deleted))
                        cacheRefresh++
                    }
                },
            ),
        ),
    )
}

@Composable
private fun DeepLKeyDialog(
    hasKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KSR.strings.translate_deepl_key)) },
        text = {
            Column {
                Text(stringResource(KSR.strings.translate_deepl_key_dialog_message))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(KSR.strings.translate_deepl_key_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(key) }, enabled = key.isNotBlank()) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            if (hasKey) {
                TextButton(onClick = onRemove) { Text(stringResource(MR.strings.action_remove)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
            }
        },
    )
}

private fun displayName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
