package io.github.oxiadenine.rpgc.command

import com.github.kotlintelegrambot.entities.ChatId
import io.github.oxiadenine.rpgc.repository.CharacterRepository
import io.github.oxiadenine.rpgc.repository.GameRepository
import io.github.oxiadenine.rpgc.repository.User
import io.github.oxiadenine.rpgc.repository.UserGameSubscriptionRepository
import io.github.oxiadenine.rpgc.view.GameInlineKeyboardMarkup
import java.util.*

class DeleteGameCommandHandler(
    context: CommandContext,
    private val gameRepository: GameRepository,
    private val userGameSubscriptionRepository: UserGameSubscriptionRepository,
    private val characterRepository: CharacterRepository
) : CommandHandler(context) {
    override suspend fun handle(message: CommandMessage?) {
        if (context.user.role != User.Role.ADMIN) {
            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(id = "command.user.unauthorized.message")
            )

            isHandled = true

            return
        }

        if (message == null) {
            val games = gameRepository.read().filter { game ->
                characterRepository.read(game.id).isEmpty()
            }.sortedBy { game -> game.name.value }

            if (games.isEmpty()) {
                context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(id = "command.game.list.empty.message")
                )

                isHandled = true
            } else {
                context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(id = "command.game.list.message"),
                    replyMarkup = GameInlineKeyboardMarkup.create(games)
                )
            }

            return
        }

        if (message.type != CommandMessage.Type.QUERY) return

        val game = try {
            gameRepository.read(UUID.fromString(message.text)) ?: return
        } catch (_: Exception) {
            return
        }

        userGameSubscriptionRepository.delete(game.id)
        gameRepository.delete(game.id)

        context.bot.sendMessage(
            chatId = ChatId.fromId(context.user.id),
            text = context.intl.translate(
                id = "command.deletegame.success.message",
                value = "gameName" to game.name.value
            )
        )

        isHandled = true
    }
}