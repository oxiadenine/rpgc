package io.github.oxiadenine.rpgcbot.view

import io.github.oxiadenine.rpgcbot.Character
import com.github.kotlintelegrambot.entities.KeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton

object CharacterKeyboardReplyMarkup {
    fun create(characters: List<Character>) = KeyboardReplyMarkup(
        keyboard = characters.map { character -> listOf(KeyboardButton(text = character.name)) },
        resizeKeyboard = true,
        oneTimeKeyboard = true
    )
}