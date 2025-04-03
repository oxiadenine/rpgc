package io.github.oxiadenine.rpgc.command

import com.github.kotlintelegrambot.entities.ChatId
import io.github.oxiadenine.rpgc.repository.User
import io.github.oxiadenine.rpgc.repository.UserRepository

class StartCommandHandler(
    context: CommandContext,
    private val userRepository: UserRepository
) : CommandHandler(context) {
    override suspend fun handle(message: CommandMessage?) {
        if (message == null) {
            userRepository.read(context.user.id)?.let { currentUser ->
                val user = User(
                    id = currentUser.id,
                    name = if (currentUser.name != context.user.name) {
                        context.user.name
                    } else currentUser.name,
                    language = context.user.language
                )

                userRepository.update(user)

                context.user = user
            } ?: userRepository.create(context.user)

            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(id = "command.start.message")
            )
        }

        isHandled = true
    }
}