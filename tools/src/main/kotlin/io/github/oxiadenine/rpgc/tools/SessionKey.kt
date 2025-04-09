@file:JvmName("SessionKey")

package io.github.oxiadenine.rpgc.tools

import io.github.oxiadenine.rpgc.tools.security.PasswordEncoder

@OptIn(ExperimentalStdlibApi::class)
fun main(args: Array<String>) {
    val argumentNames = arrayOf("p", "pass", "password")

    if (args.isEmpty()) {
        error("Empty args, usage: --password <value>")
    }

    if (args.size < 2 || args[0].substringAfterLast("-") !in argumentNames) {
        error("Invalid args, usage: --password <value>")
    }

    val passwordEncoder = PasswordEncoder()

    val salt = passwordEncoder.generateBytes(16)
    val secretKey = passwordEncoder.hash(args[1], salt)

    println("${secretKey.toHexString()}$${salt.toHexString()}")
}