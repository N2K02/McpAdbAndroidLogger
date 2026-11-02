package dev.n2k02.mcplogger.adb

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/** One disposable RSA private key object per libadb manager. */
internal object AdbSessionKey {
    fun copyOf(persistent: PrivateKey): PrivateKey {
        val encoded = requireNotNull(persistent.encoded) {
            "ADB identity must be exportable for session reuse"
        }
        return KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(encoded))
    }
}
