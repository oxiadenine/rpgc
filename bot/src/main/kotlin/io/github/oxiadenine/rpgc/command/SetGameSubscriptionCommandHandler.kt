package io.github.oxiadenine.rpgc.command

import com.github.kotlintelegrambot.entities.ChatId
import io.github.oxiadenine.rpgc.repository.GameRepository
import io.github.oxiadenine.rpgc.repository.User
import io.github.oxiadenine.rpgc.repository.UserGameSubscription
import io.github.oxiadenine.rpgc.repository.UserGameSubscriptionRepository
import io.github.oxiadenine.rpgc.view.GameSubscriptionInlineKeyboardMarkup
import java.util.*

class SetGameSubscriptionCommandHandler(
    context: CommandContext,
    private val gameRepository: GameRepository,
    private val userGameSubscriptionRepository: UserGameSubscriptionRepository
) : CommandHandler(context) {
    override suspend fun handle(message: CommandMessage?) {
        if (context.user.role == User.Role.NORMAL) {
            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(id = "command.user.unauthorized.message")
            )

            isHandled = true

            return
        }

        if (message == null) {
            val games = gameRepository.read().sortedBy { game -> game.name.value }

            if (games.isEmpty()) {
                context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(id = "command.game.list.empty.message")
                )

                isHandled = true
            } else {
                val userGameSubscriptions = userGameSubscriptionRepository.read().filter { userGameSubscription ->
                    userGameSubscription.userId == context.user.id
                }

                context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(id = "command.game.list.message"),
                    replyMarkup = GameSubscriptionInlineKeyboardMarkup.create(games, userGameSubscriptions)
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

        userGameSubscriptionRepository.read(context.user.id, game.id)?.run {
            userGameSubscriptionRepository.delete(userId, game.id)

            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(
                    id = "command.setgamesub.unsubscribe.success.message",
                    value = "gameName" to game.name.value
                )
            )
        } ?: run {
            val userGameSubscription = UserGameSubscription(context.user.id, game.id)

            userGameSubscriptionRepository.create(userGameSubscription)

            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(
                    id = "command.setgamesub.subscribe.success.message",
                    value = "gameName" to game.name.value
                )
            )
        }

        isHandled = true
    }
}