package io.github.oxiadenine.rpgc.command

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.TelegramFile
import io.github.oxiadenine.rpgc.normalize
import io.github.oxiadenine.rpgc.repository.CharacterImageRepository
import io.github.oxiadenine.rpgc.repository.CharacterRepository
import io.github.oxiadenine.rpgc.repository.GameRepository
import io.github.oxiadenine.rpgc.repository.toCommandName

class CharacterImageCommandHandler(
    context: CommandContext,
    private val gameRepository: GameRepository,
    private val characterRepository: CharacterRepository,
    private val characterImageRepository: CharacterImageRepository
) : CommandHandler(context) {
    override suspend fun handle(message: CommandMessage?) {
        if (message == null) {
            gameRepository.read().firstOrNull { game ->
                game.name.toCommandName() == context.command.name
            }?.let { game ->
                if (context.command.args.isEmpty()) {
                    isHandled = true

                    return
                }

                val nameKeywords = context.command.args.map { nameKeyword ->
                    nameKeyword.normalize().replace("[^a-zA-Z0-9 ]".toRegex(), "")
                }

                if (nameKeywords.joinToString("").length < 3) {
                    isHandled = true

                    return
                }

                characterRepository.read(game.id).filter { character ->
                    val characterName = character.name.value
                        .normalize()
                        .replace("[^a-zA-Z0-9 ]".toRegex(), "")

                    if (nameKeywords.size > 1) {
                        val partialNames = characterName.split(" ")

                        nameKeywords.withIndex().all { nameKeyword ->
                            val partialName = partialNames.getOrElse(nameKeyword.index) { "" }

                            if (
                                nameKeyword.value.length > 3 &&
                                (partialName.isEmpty() || partialName.length < 3)
                            ) false else partialName.contains(nameKeyword.value, true)
                        }
                    } else characterName.contains(nameKeywords[0], true)
                }.forEach { character ->
                    characterImageRepository.read(character.id)?.let { characterImage ->
                        context.bot.sendDocument(
                            chatId = ChatId.fromId(context.user.id),
                            document = TelegramFile.ByByteArray(
                                fileBytes = characterImage.bytes,
                                filename = "${characterImage.name}.${characterImage.type}"
                            )
                        )
                    }
                }
            } ?: run {
                if (context.command.name.length < 3) {
                    isHandled = true

                    return
                }

                characterRepository.read().filter { character ->
                    character.name.toCommandName().contains(context.command.name, true)
                }.forEach { character ->
                    characterImageRepository.read(character.id)?.let { characterImage ->
                        context.bot.sendDocument(
                            chatId = ChatId.fromId(context.user.id),
                            document = TelegramFile.ByByteArray(
                                fileBytes = characterImage.bytes,
                                filename = "${characterImage.name}.${characterImage.type}"
                            )
                        )
                    }
                }
            }
        }

        isHandled = true
    }
}