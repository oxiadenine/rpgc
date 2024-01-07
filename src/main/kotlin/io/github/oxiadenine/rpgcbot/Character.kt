package io.github.oxiadenine.rpgcbot

import java.lang.IllegalArgumentException
import java.lang.IllegalStateException

class Character {
    @JvmInline
    value class Name(val value: String) {
        class BlankException : IllegalArgumentException()
        class LengthException : IllegalArgumentException()
        class InvalidException : IllegalArgumentException()
        class ExistsException : IllegalStateException()

        init {
            if (value.isBlank()) {
                throw BlankException()
            }

            if (value.length > 64) {
                throw LengthException()
            }

            if (!value.matches("^[a-z A-Z]+$".toRegex())) {
                throw InvalidException()
            }
        }
    }

    @JvmInline
    value class Description(val value: String) {
        class BlankException : IllegalArgumentException()
        class LengthException : IllegalArgumentException()

        init {
            if (value.isBlank()) {
                throw BlankException()
            }

            if (value.length > 64000) {
                throw LengthException()
            }
        }
    }

    var id = ""
    var name = ""
    var description = ""
}