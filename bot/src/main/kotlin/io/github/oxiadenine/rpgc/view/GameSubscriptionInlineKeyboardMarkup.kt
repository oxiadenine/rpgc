package io.github.oxiadenine.rpgc.view

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import io.github.oxiadenine.rpgc.repository.Game
import io.github.oxiadenine.rpgc.repository.UserGameSubscription

object GameSubscriptionInlineKeyboardMarkup {
    fun create(
        games: List<Game>,
        userGameSubscriptions: List<UserGameSubscription>
    ) = InlineKeyboardMarkup.create(games.map { game ->
        val hasUserGameSubscription = userGameSubscriptions.any { userGameSubscription ->
            userGameSubscription.gameId == game.id
        }

        listOf(InlineKeyboardButton.CallbackData(text = if (hasUserGameSubscription) {
            "\uD83D\uDD14 ${game.name.value}"
        } else game.name.value, callbackData = game.id.toString()))
    })
}