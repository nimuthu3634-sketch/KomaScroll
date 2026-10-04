package komascroll.immersion.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import komascroll.i18n.KSR
import komascroll.immersion.PinHasher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The KomaScroll lock screen: a PIN pad plus optional fingerprint / face unlock. Biometrics always
 * unlock the real library; only the decoy PIN opens the decoy one.
 */
@Composable
fun PinLockScreen(
    activity: FragmentActivity,
    appLock: AppLock,
    biometricAllowed: Boolean,
    onUnlocked: (decoy: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var lockout by remember { mutableLongStateOf(appLock.lockoutRemaining()) }
    val canUseBiometric = remember(biometricAllowed) { biometricAllowed && activity.canUseBiometric() }

    LaunchedEffect(lockout) {
        if (lockout > 0) {
            delay(1_000)
            lockout = appLock.lockoutRemaining()
        }
    }
    LaunchedEffect(Unit) {
        if (canUseBiometric) activity.promptBiometric { onUnlocked(false) }
    }

    fun submit() {
        if (checking || pin.length < PinHasher.MIN_LENGTH) return
        checking = true
        val entered = pin
        scope.launch {
            when (appLock.check(entered)) {
                AppLock.Result.UNLOCKED -> onUnlocked(false)
                AppLock.Result.DECOY -> onUnlocked(true)
                AppLock.Result.WRONG -> {
                    error = activity.stringResource(KSR.strings.lock_wrong_pin)
                    lockout = appLock.lockoutRemaining()
                }
                AppLock.Result.LOCKED_OUT -> lockout = appLock.lockoutRemaining()
            }
            pin = ""
            checking = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(MR.strings.unlock_app_title, stringResource(MR.strings.app_name)),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = if (pin.isEmpty()) " " else "●".repeat(pin.length),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            val message = when {
                lockout > 0 -> stringResource(KSR.strings.lock_locked_out, (lockout + 999) / 1000)
                else -> error ?: ""
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))

            val enabled = lockout <= 0 && !checking
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { digit ->
                        DigitKey(digit, enabled) {
                            if (pin.length < PinHasher.MAX_LENGTH) pin += digit
                            error = null
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { pin = pin.dropLast(1) }, enabled = pin.isNotEmpty()) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Backspace,
                            contentDescription = stringResource(KSR.strings.lock_delete_digit),
                        )
                    }
                }
                DigitKey("0", enabled) {
                    if (pin.length < PinHasher.MAX_LENGTH) pin += "0"
                    error = null
                }
                Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                    FilledTonalIconButton(
                        onClick = ::submit,
                        enabled = enabled && pin.length >= PinHasher.MIN_LENGTH,
                        modifier = Modifier.size(KEY_SIZE),
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = stringResource(KSR.strings.lock_unlock))
                    }
                }
            }
            if (canUseBiometric) {
                Spacer(Modifier.height(20.dp))
                IconButton(onClick = { activity.promptBiometric { onUnlocked(false) } }) {
                    Icon(
                        Icons.Outlined.Fingerprint,
                        contentDescription = stringResource(KSR.strings.lock_use_biometric),
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
    }
}

private val KEY_SIZE = 72.dp

@Composable
private fun DigitKey(digit: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.size(KEY_SIZE),
    ) {
        Text(digit, style = MaterialTheme.typography.headlineSmall)
    }
}

/** Class 2 (weak) or stronger biometrics; no device credential, the PIN pad replaces it. */
private const val BIOMETRICS = Authenticators.BIOMETRIC_WEAK

private fun FragmentActivity.canUseBiometric(): Boolean =
    BiometricManager.from(this).canAuthenticate(BIOMETRICS) == BiometricManager.BIOMETRIC_SUCCESS

private fun FragmentActivity.promptBiometric(onSuccess: () -> Unit) {
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(this.stringResource(MR.strings.unlock_app_title, this.stringResource(MR.strings.app_name)))
        .setNegativeButtonText(this.stringResource(KSR.strings.lock_use_pin))
        .setAllowedAuthenticators(BIOMETRICS)
        .build()
    prompt.authenticate(info)
}
