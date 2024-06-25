package io.github.oxiadenine.rpgcbot

import java.lang.IllegalArgumentException
import java.lang.IllegalStateException

class CharacterPage {
    enum class Paths { RANKING }

    @JvmInline
    value class Title(val value: String) {
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

            if (!value.matches("^([A-Za-zÀ-ÖØ-öø-ÿ0-9.]+\\s?)+$".toRegex())) {
                throw InvalidException()
            }
        }
    }

    @JvmInline
    value class Content(val value: String) {
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

    var path = ""
    var title = ""
    var content = ""
    var url = ""
    var isRanking = false
    var image: ByteArray? = null
}