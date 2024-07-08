package io.github.oxiadenine.rpgcbot.view

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import io.github.oxiadenine.rpgcbot.repository.Game

object GameInlineKeyboardMarkup {
    fun create(games: List<Game>) = InlineKeyboardMarkup.create(games.map { game ->
        listOf(InlineKeyboardButton.CallbackData(text = game.name.value, callbackData = game.key))
    })
}