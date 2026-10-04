package komascroll.immersion

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2 hashes for app-lock PINs. Only the hash is stored (and that inside encrypted
 * storage), so the PIN itself can never be read back.
 *
 * Format: `v1:<iterations>:<salt base64>:<hash base64>`.
 */
object PinHasher {

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    private const val VERSION = "v1"
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 60_000
    private const val SALT_BYTES = 16
    private const val HASH_BITS = 256

    fun isValidPin(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun hash(pin: String, random: SecureRandom = SecureRandom()): String {
        require(isValidPin(pin)) { "A PIN is $MIN_LENGTH to $MAX_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(pin, salt, ITERATIONS)
        val encoder = Base64.getEncoder()
        return "$VERSION:$ITERATIONS:${encoder.encodeToString(salt)}:${encoder.encodeToString(hash)}"
    }

    /** Whether [pin] matches [stored]; malformed stored values never match. */
    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 4 || parts[0] != VERSION) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        return try {
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(parts[2])
            val expected = decoder.decode(parts[3])
            MessageDigest.isEqual(derive(pin, salt, iterations), expected)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/** How long the lock screen refuses PINs after repeated wrong attempts. */
object LockoutPolicy {

    /** Wrong attempts allowed before the first lockout. */
    const val FREE_ATTEMPTS = 5

    private const val FIRST_LOCKOUT_MILLIS = 30_000L
    private const val MAX_LOCKOUT_MILLIS = 15 * 60_000L

    /** Lockout after [failures] consecutive wrong PINs: 30 s, doubling per further failure, at most 15 min. */
    fun lockoutMillis(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(10)
        return (FIRST_LOCKOUT_MILLIS shl doublings).coerceAtMost(MAX_LOCKOUT_MILLIS)
    }
}
