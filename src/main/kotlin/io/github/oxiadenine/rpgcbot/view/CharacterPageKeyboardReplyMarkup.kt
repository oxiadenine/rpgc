package io.github.oxiadenine.rpgcbot.view

import io.github.oxiadenine.rpgcbot.CharacterPage
import com.github.kotlintelegrambot.entities.KeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton

object CharacterPageKeyboardReplyMarkup {
    fun create(characterPages: List<CharacterPage>) = KeyboardReplyMarkup(
        keyboard = characterPages.map { characterPage -> listOf(KeyboardButton(text = characterPage.title.value)) },
        resizeKeyboard = true,
        oneTimeKeyboard = true
    )
}