package io.github.oxiadenine.rpgc.bot.command

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ReplyKeyboardRemove
import java.util.concurrent.ConcurrentLinkedQueue

class CancelCommandHandler(
    context: CommandContext,
    private val commandHandlers: ConcurrentLinkedQueue<CommandHandler>
) : CommandHandler(context) {
    override suspend fun handle(message: CommandMessage?) {
        if (message == null) {
            commandHandlers.filter { commandHandler ->
                commandHandler.context.command.name != Command.Name.CANCEL.text
            }.firstOrNull { commandHandler ->
                commandHandler.context.user.id == context.user.id
            }?.let { commandHandler ->
                commandHandlers.remove(commandHandler)

                context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(
                        id = "command.cancel.success.message",
                        value = "commandName" to commandHandler.context.command.name
                    ),
                    replyMarkup = ReplyKeyboardRemove()
                )
            }
        }

        isHandled = true
    }
}