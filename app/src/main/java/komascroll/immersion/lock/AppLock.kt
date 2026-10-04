package komascroll.immersion.lock

import android.content.Context
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import komascroll.core.SecretStore
import komascroll.immersion.LockoutPolicy
import komascroll.immersion.PinHasher
import komascroll.lab.LabPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KomaScroll's PIN app lock. The real PIN unlocks the app; an optional second, decoy PIN unlocks it
 * into a [DecoySession] that only shows the decoy library.
 *
 * Only salted PBKDF2 hashes of the PINs are kept, in [SecretStore] (Android Keystore encrypted and
 * excluded from backups). When and how often the app locks follows Settings → Security.
 */
class AppLock(
    context: Context,
    private val preferences: LabPreferences,
    private val securityPreferences: SecurityPreferences,
    private val secrets: SecretStore,
) {
    enum class Result { UNLOCKED, DECOY, WRONG, LOCKED_OUT }

    private val attempts = context.applicationContext.getSharedPreferences(ATTEMPTS_PREFS, Context.MODE_PRIVATE)

    fun hasPin(): Boolean = secrets.contains(PIN_KEY)

    fun hasDecoyPin(): Boolean = secrets.contains(DECOY_PIN_KEY)

    /** Whether the PIN screen replaces the system unlock prompt. */
    fun isEnabled(): Boolean = preferences.appLockEnabled().get() && hasPin()

    /** Sets the PIN and turns the lock on (app lock timing comes from the security settings). */
    suspend fun enable(pin: String) {
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin) }
        secrets.put(PIN_KEY, hash)
        preferences.appLockEnabled().set(true)
        securityPreferences.useAuthenticator().set(true)
        resetAttempts()
    }

    fun disable() {
        preferences.appLockEnabled().set(false)
        secrets.remove(PIN_KEY)
        secrets.remove(DECOY_PIN_KEY)
        resetAttempts()
        DecoySession.end()
    }

    /** False when [pin] is the real PIN: the two must differ. */
    suspend fun setDecoyPin(pin: String): Boolean {
        if (matches(PIN_KEY, pin)) return false
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin) }
        secrets.put(DECOY_PIN_KEY, hash)
        return true
    }

    fun removeDecoyPin() {
        secrets.remove(DECOY_PIN_KEY)
    }

    /** Checks the real PIN only (used to confirm changes in settings). */
    suspend fun isRealPin(pin: String): Boolean = matches(PIN_KEY, pin)

    /** Milliseconds until PINs are accepted again after too many wrong attempts; 0 when not locked out. */
    fun lockoutRemaining(): Long = (attempts.getLong(LOCKED_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    suspend fun check(pin: String): Result {
        if (lockoutRemaining() > 0) return Result.LOCKED_OUT
        return when {
            matches(PIN_KEY, pin) -> {
                resetAttempts()
                Result.UNLOCKED
            }
            matches(DECOY_PIN_KEY, pin) -> {
                resetAttempts()
                Result.DECOY
            }
            else -> {
                val failures = attempts.getInt(FAILURES, 0) + 1
                val lockout = LockoutPolicy.lockoutMillis(failures)
                attempts.edit()
                    .putInt(FAILURES, failures)
                    .putLong(LOCKED_UNTIL, if (lockout > 0) System.currentTimeMillis() + lockout else 0L)
                    .apply()
                Result.WRONG
            }
        }
    }

    private suspend fun matches(key: String, pin: String): Boolean = withContext(Dispatchers.Default) {
        val stored = secrets.get(key) ?: return@withContext false
        PinHasher.verify(pin, stored)
    }

    private fun resetAttempts() {
        attempts.edit().remove(FAILURES).remove(LOCKED_UNTIL).apply()
    }

    private companion object {
        const val PIN_KEY = "app_lock_pin"
        const val DECOY_PIN_KEY = "app_lock_decoy_pin"
        const val ATTEMPTS_PREFS = "komascroll_lock"
        const val FAILURES = "failures"
        const val LOCKED_UNTIL = "locked_until"
    }
}
