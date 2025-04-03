package io.github.oxiadenine.rpgc.command

import com.github.kotlintelegrambot.entities.ChatId
import io.github.oxiadenine.rpgc.normalize
import io.github.oxiadenine.rpgc.repository.Game
import io.github.oxiadenine.rpgc.repository.GameRepository
import io.github.oxiadenine.rpgc.repository.User

class NewGameCommandHandler(
    context: CommandContext,
    private val gameRepository: GameRepository,
    private var game: Game = Game()
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
            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(id = "command.newgame.name.message")
            )

            return
        }

        if (message.type != CommandMessage.Type.TEXT) return

        val gameName = try {
            Game.Name(message.text)
        } catch (exception: Game.NameException) {
            when (exception) {
                is Game.NameException.Blank -> context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(id = "command.game.name.blank.message")
                )
                is Game.NameException.Length -> context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(id = "command.game.name.length.message")
                )
                is Game.NameException.Invalid -> context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(id = "command.game.name.invalid.message")
                )
            }

            return
        }

        val gameExists = gameRepository.read().any { game ->
            game.name.value.normalize().equals(gameName.value.normalize(), true)
        }

        if (gameExists) {
            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(id = "command.game.name.exists.message")
            )

            return
        }

        game = Game(id = game.id, name = gameName)

        gameRepository.create(game)

        context.bot.sendMessage(
            chatId = ChatId.fromId(context.user.id),
            text = context.intl.translate(
                id = "command.newgame.success.message",
                value = "gameName" to game.name.value
            )
        )

        isHandled = true
    }
}