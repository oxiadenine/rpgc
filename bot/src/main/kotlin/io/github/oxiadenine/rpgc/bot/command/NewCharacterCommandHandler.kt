package io.github.oxiadenine.rpgc.bot.command

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.TelegramFile
import io.github.oxiadenine.rpgc.bot.Intl
import io.github.oxiadenine.rpgc.bot.getAndCreateTempFile
import io.github.oxiadenine.rpgc.bot.graphic.CharacterImageRenderer
import io.github.oxiadenine.rpgc.bot.sendDocumentAndGetFileId
import io.github.oxiadenine.rpgc.bot.view.GameInlineKeyboardMarkup
import io.github.oxiadenine.rpgc.common.normalize
import io.github.oxiadenine.rpgc.common.repository.*
import java.util.*

class NewCharacterCommandHandler(
    context: CommandContext,
    private val userRepository: UserRepository,
    private val gameRepository: GameRepository,
    private val userGameSubscriptionRepository: UserGameSubscriptionRepository,
    private val characterRepository: CharacterRepository,
    private val characterImageRepository: CharacterImageRepository,
    private val characterImageRenderer: CharacterImageRenderer,
    private val characterImageChannelUsername: String,
    private var game: Game = Game(),
    private var character: Character = Character()
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
            val games = gameRepository.read().sortedBy { game -> game.name.value }

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

            val isCharacterRanking = context.command.name != Command.Name.NEW_CHARACTER.text

            game.characters = characterRepository.read(game.id).filter { character ->
                character.isRanking == isCharacterRanking
            }

            character = Character(isRanking = isCharacterRanking, game = game)

            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(id = if (isCharacterRanking) {
                    "command.newcharrank.name.message"
                } else "command.newchar.name.message")
            )

            return
        }

        if (character.name.value.isEmpty()) {
            if (message.type != CommandMessage.Type.TEXT) return

            val characterName = try {
                Character.Name(message.text)
            } catch (exception: Character.NameException) {
                when (exception) {
                    is Character.NameException.Blank -> context.bot.sendMessage(
                        chatId = ChatId.fromId(context.user.id),
                        text = context.intl.translate(id = if (character.isRanking) {
                            "command.character.ranking.name.blank.message"
                        } else "command.character.name.blank.message")
                    )
                    is Character.NameException.Length -> context.bot.sendMessage(
                        chatId = ChatId.fromId(context.user.id),
                        text = context.intl.translate(id = if (character.isRanking) {
                            "command.character.ranking.name.length.message"
                        } else "command.character.name.length.message")
                    )
                    is Character.NameException.Invalid -> context.bot.sendMessage(
                        chatId = ChatId.fromId(context.user.id),
                        text = context.intl.translate(id = if (character.isRanking) {
                            "command.character.ranking.name.invalid.message"
                        } else "command.character.name.invalid.message")
                    )
                }

                return
            }

            val characterExists = game.characters.any { character ->
                character.name.value.normalize().equals(characterName.value.normalize(), true)
            }

            if (characterExists) {
                context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(id = if (character.isRanking) {
                        "command.character.ranking.name.exists.message"
                    } else "command.character.name.exists.message")
                )

                return
            }

            character = Character(
                id = character.id,
                name = characterName,
                isRanking = character.isRanking,
                game = character.game
            )

            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(id = if (character.isRanking) {
                    "command.newcharrank.content.message"
                } else "command.newchar.content.message")
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
                    text = context.intl.translate(id = "command.newcharrank.content.image.message")
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

            val characterImage = try {
                characterImageRenderer.render(character, width = 2048)
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

            characterImage.apply {
                id = context.bot.sendDocumentAndGetFileId(
                    chatId = ChatId.fromChannelUsername(characterImageChannelUsername),
                    document = TelegramFile.ByByteArray(bytes, "$name.$type")
                )
            }

            characterRepository.create(character)
            characterImageRepository.create(characterImage)

            context.bot.sendMessage(
                chatId = ChatId.fromId(context.user.id),
                text = context.intl.translate(
                    id = if (character.isRanking) {
                        "command.newcharrank.success.message"
                    } else "command.newchar.success.message",
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
                            "command.setgamesub.character.ranking.created.message"
                        } else "command.setgamesub.character.created.message",
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