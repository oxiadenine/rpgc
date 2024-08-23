package io.github.oxiadenine.rpgcbot.view

import com.github.kotlintelegrambot.entities.KeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import io.github.oxiadenine.rpgcbot.repository.Character

object CharacterKeyboardReplyMarkup {
    fun create(characters: List<Character>) = KeyboardReplyMarkup(
        keyboard = characters.map { character ->
            listOf(KeyboardButton(text = character.name.value))
        },
        resizeKeyboard = true,
        oneTimeKeyboard = true
    )
}