package io.github.oxiadenine.rpgc.command

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ReplyKeyboardRemove
import com.github.kotlintelegrambot.entities.TelegramFile
import io.github.oxiadenine.rpgc.Intl
import io.github.oxiadenine.rpgc.getAndCreateTempFile
import io.github.oxiadenine.rpgc.sendDocumentAndGetFileId
import io.github.oxiadenine.rpgc.repository.*
import io.github.oxiadenine.rpgc.view.CharacterKeyboardReplyMarkup
import io.github.oxiadenine.rpgc.view.GameInlineKeyboardMarkup
import java.util.*

class EditCharacterCommandHandler(
    context: CommandContext,
    private val userRepository: UserRepository,
    private val gameRepository: GameRepository,
    private val userGameSubscriptionRepository: UserGameSubscriptionRepository,
    private val characterRepository: CharacterRepository,
    private val characterImageRepository: CharacterImageRepository,
    private val characterTemplatePath: String,
    private val channelUsername: String,
    private var game: Game = Game(),
    private var character: Character = Character()
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
            val games = gameRepository.read().filter { game ->
                characterRepository.read(game.id).any { character ->
                    if (context.command.name == Command.Name.EDIT_CHARACTER.text) {
                        !character.isRanking
                    } else character.isRanking
                }
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

        if (game.name.value.isEmpty()) {
            if (message.type != CommandMessage.Type.QUERY) return

            game = try {
                gameRepository.read(UUID.fromString(message.text)) ?: return
            } catch (_: Exception) {
                return
            }

            val isCharacterRanking = context.command.name != Command.Name.EDIT_CHARACTER.text

            game.characters = characterRepository.read(game.id).filter { character ->
                character.isRanking == isCharacterRanking
            }.sortedBy { character -> character.name.value }

            character = Character(isRanking = isCharacterRanking, game = game)

            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(id = if (isCharacterRanking) {
                    "command.editcharrank.character.list.message"
                } else "command.editchar.character.list.message"),
                replyMarkup = CharacterKeyboardReplyMarkup.create(game.characters)
            )

            return
        }

        if (character.name.value.isEmpty()) {
            if (message.type != CommandMessage.Type.TEXT) return

            val characterName = Character.Name(message.text)

            val currentCharacter = game.characters.firstOrNull { character ->
                character.name.value == characterName.value
            } ?: return

            character = Character(
                id = currentCharacter.id,
                name = currentCharacter.name,
                isRanking = character.isRanking,
                game = character.game
            )

            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(id = if (character.isRanking) {
                    "command.editcharrank.content.message1"
                } else "command.editchar.content.message1"),
                replyMarkup = ReplyKeyboardRemove()
            )

            context.bot.sendMessage(chatId = ChatId.fromId(context.user.id), text = currentCharacter.content.value)

            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(id = if (character.isRanking) {
                    "command.editcharrank.content.message2"
                } else "command.editchar.content.message2")
            )

            return
        }

        if (character.content.value.isEmpty()) {
            if (message.type != CommandMessage.Type.TEXT) return

            val characterContent = try {
                Character.Content(message.text)
            } catch (exception: Character.ContentException) {
                when (exception) {
                    is Character.ContentException.Blank -> context.bot.sendMessage(
                        chatId = ChatId.fromId(context.user.id),
                        text = context.intl.translate(id = if (character.isRanking) {
                            "command.character.ranking.content.blank.message"
                        } else "command.character.content.blank.message")
                    )
                    is Character.ContentException.Length -> context.bot.sendMessage(
                        chatId = ChatId.fromId(context.user.id),
                        text = context.intl.translate(id = if (character.isRanking) {
                            "command.character.ranking.content.length.message"
                        } else "command.character.content.length.message")
                    )
                }

                return
            }

            character = Character(
                id = character.id,
                name = character.name,
                content = characterContent,
                isRanking = character.isRanking,
                game = character.game
            )

            if (character.isRanking) {
                context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(id = "command.editcharrank.content.image.message")
                )

                return
            }
        }

        if (character.content.value.isNotEmpty()) {
            if (character.isRanking) {
                if (message.type != CommandMessage.Type.IMAGE) return

                val imageFilePath = context.bot.getAndCreateTempFile(message.text).absolutePath

                val characterContent = Character.Content(character.content.value, imageFilePath)

                character = Character(
                    id = character.id,
                    name = character.name,
                    content = characterContent,
                    isRanking = character.isRanking,
                    game = character.game
                )
            }

            val characterImageName = character.name.toFileName()
            val characterImageBytes = try {
                character.renderToImage(characterTemplatePath, width = 2048)
            } catch (_: Exception) {
                character = Character(
                    id = character.id,
                    name = character.name,
                    isRanking = character.isRanking,
                    game = character.game
                )

                context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(id = if (character.isRanking) {
                        "command.character.ranking.content.invalid.message"
                    } else "command.character.content.invalid.message")
                )

                return
            }

            val characterImage = CharacterImage(
                name = characterImageName,
                bytes = characterImageBytes,
                characterId = character.id
            ).apply {
                id = context.bot.sendDocumentAndGetFileId(
                    chatId = ChatId.fromChannelUsername(channelUsername),
                    document = TelegramFile.ByByteArray(bytes, "$name.$type")
                )
            }

            characterRepository.update(character)

            characterImageRepository.read(character.id)?.let { currentCharacterImage ->
                characterImageRepository.delete(currentCharacterImage.id)
            }
            characterImageRepository.create(characterImage)

            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(
                    id = if (character.isRanking) {
                        "command.editcharrank.success.message"
                    } else "command.editchar.success.message",
                    value = "characterName" to character.name.value
                )
            )

            userGameSubscriptionRepository.read(game.id).filter { userGameSubscription ->
                userGameSubscription.userId != context.user.id
            }.forEach { userGameSubscription ->
                val intl = Intl(userRepository.read(userGameSubscription.userId)!!.language)

                context.bot.sendMessage(
                    chatId = ChatId.fromId(userGameSubscription.userId),
                    text = intl.translate(
                        id = if (character.isRanking) {
                            "command.setgamesub.character.ranking.edited.message"
                        } else "command.setgamesub.character.edited.message",
                        values = listOf(
                            "userName" to context.user.name,
                            "characterName" to character.name.value
                        )
                    )
                )
            }

            isHandled = true

            return
        }
    }
}