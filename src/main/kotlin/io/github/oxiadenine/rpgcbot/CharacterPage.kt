package io.github.oxiadenine.rpgcbot

class CharacterPage {
    enum class Paths { RANKING }

    class Title(title: String? = null) {
        class BlankError : Error()
        class LengthError : Error()
        class InvalidError : Error()
        class ExistsError : Error()

        val value: String = title?.let {
            if (title.isBlank()) {
                throw BlankError()
            }

            if (title.length > 64) {
                throw LengthError()
            }

            if (!title.matches("^([A-Za-zÀ-ÖØ-öø-ÿ0-9.]+\\s?)+$".toRegex())) {
                throw InvalidError()
            }

            title
        } ?: ""
    }

    class Content(content: String? = null) {
        class BlankError : Error()
        class LengthError : Error()

        val value = content?.let {
            if (content.isBlank()) {
                throw BlankError()
            }

            if (content.length > 64000) {
                throw LengthError()
            }

            content
        } ?: ""
    }

    var path = ""
    var title = Title()
    var content = Content()
    var url = ""
    var isRanking = false
    var image: ByteArray? = null
}