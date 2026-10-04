package eu.kanade.tachiyomi.ui.security

import android.os.Bundle
import androidx.activity.addCallback
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.startAuthentication
import eu.kanade.tachiyomi.util.view.setComposeContent
import komascroll.immersion.lock.AppLock
import komascroll.immersion.lock.DecoySession
import komascroll.immersion.lock.PinLockScreen
import komascroll.lab.LabPreferences
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Blank activity with a BiometricPrompt.
 */
class UnlockActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // KS -->
        val appLock = Injekt.get<AppLock>()
        if (appLock.isEnabled()) {
            showPinLock(appLock)
            return
        }
        // KS <--
        startAuthentication(
            stringResource(MR.strings.unlock_app_title, stringResource(MR.strings.app_name)),
            confirmationRequired = false,
            callback = object : AuthenticatorUtil.AuthenticationCallback() {
                override fun onAuthenticationError(
                    activity: FragmentActivity?,
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    super.onAuthenticationError(activity, errorCode, errString)
                    logcat(LogPriority.ERROR) { errString.toString() }
                    finishAffinity()
                }

                override fun onAuthenticationSucceeded(
                    activity: FragmentActivity?,
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    super.onAuthenticationSucceeded(activity, result)
                    SecureActivityDelegate.unlock()
                    finish()
                }
            },
        )
    }

    // KS -->
    private fun showPinLock(appLock: AppLock) {
        onBackPressedDispatcher.addCallback(this) { finishAffinity() }
        setComposeContent {
            PinLockScreen(
                activity = this,
                appLock = appLock,
                biometricAllowed = Injekt.get<LabPreferences>().appLockBiometric().get(),
                onUnlocked = { decoy ->
                    if (decoy) DecoySession.start() else DecoySession.end()
                    SecureActivityDelegate.unlock()
                    finish()
                },
            )
        }
    }
    // KS <--
}
