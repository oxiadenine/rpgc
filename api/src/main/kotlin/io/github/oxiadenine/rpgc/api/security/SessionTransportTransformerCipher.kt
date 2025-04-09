package io.github.oxiadenine.rpgc.api.security

import io.github.oxiadenine.rpgc.tools.security.PasswordEncoder
import io.ktor.server.sessions.*
import io.ktor.util.*

class SessionTransportTransformerCipher(
    private val sessionKey: String,
    private val passwordEncoder: PasswordEncoder
) : SessionTransportTransformer {
    override fun transformRead(transportValue: String): String? {
        try {
            val secretKey = sessionKey.split("$")[0]
            val (sessionEncrypted, iv) = transportValue.split("/")

            val session = passwordEncoder.decrypt(hex(sessionEncrypted), hex(secretKey), hex(iv))

            return session.toString(Charsets.UTF_8)
        } catch (_: Throwable) {
            return null
        }
    }

    override fun transformWrite(transportValue: String): String {
        val secretKey = sessionKey.split("$")[0]
        val iv = passwordEncoder.generateBytes(16)
        val sessionEncrypted = passwordEncoder.encrypt(transportValue.toByteArray(), hex(secretKey), iv)

        return "${hex(sessionEncrypted)}/${hex(iv)}"
    }
}