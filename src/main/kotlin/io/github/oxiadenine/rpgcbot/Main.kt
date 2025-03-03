package io.github.oxiadenine.rpgcbot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.inlineQuery
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ReplyKeyboardRemove
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.inlinequeryresults.InlineQueryResult
import com.github.kotlintelegrambot.extensions.filters.Filter
import com.typesafe.config.ConfigFactory
import io.github.oxiadenine.rpgcbot.repository.*
import io.github.oxiadenine.rpgcbot.view.CharacterKeyboardReplyMarkup
import io.github.oxiadenine.rpgcbot.view.GameInlineKeyboardMarkup
import io.github.oxiadenine.rpgcbot.view.UserGameSubscriptionInlineKeyboardMarkup
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

enum class Command {
    START,
    NEWGAME,
    DELETEGAME,
    SETGAMESUB,
    NEWCHAR,
    EDITCHAR,
    NEWCHARRANK,
    EDITCHARRANK,
    CANCEL
}

fun Application.bot(
    userRepository: UserRepository,
    gameRepository: GameRepository,
    userGameSubscriptionRepository: UserGameSubscriptionRepository,
    characterRepository: CharacterRepository,
    characterImageRepository: CharacterImageRepository
) {
    val appConfig =  environment.config

    val characterTemplatePath = appConfig.property("bot.character.templatePath").getString()

    val bot = bot {
        token = appConfig.property("telegram.token").getString()

        val currentCommandMap = ConcurrentHashMap<Long, Command>()
        val currentGameMap = ConcurrentHashMap<Long, Game>()
        val currentCharacterMap = ConcurrentHashMap<Long, Character>()

        val channelUsername = appConfig.property("telegram.channelUsername").getString()

        dispatch {
            message(Filter.Command) {
                val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = message.chat.id

                val commandName = message.text!!.substringAfter("/").uppercase()

                if (commandName == Command.START.name) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(userId),
                        text = intl.translate(id = "command.start.message")
                    )

                    return@message
                } else if (commandName != Command.NEWGAME.name &&
                    commandName != Command.DELETEGAME.name &&
                    commandName != Command.SETGAMESUB.name &&
                    commandName != Command.NEWCHAR.name &&
                    commandName != Command.EDITCHAR.name &&
                    commandName != Command.NEWCHARRANK.name &&
                    commandName != Command.EDITCHARRANK.name &&
                    commandName != Command.CANCEL.name) {
                    if (commandName.length < 3) return@message

                    characterRepository.read().filter { character ->
                        character.name.toCommandName().contains(commandName, true)
                    }.forEach { character ->
                        characterImageRepository.read(character.id)?.let { characterImage ->
                            bot.sendDocument(
                                chatId = ChatId.fromId(userId),
                                document = TelegramFile.ByByteArray(
                                    fileBytes = characterImage.bytes,
                                    filename = "${characterImage.name}.${characterImage.type}"
                                )
                            )
                        }
                    }

                    return@message
                }

                runCatching {
                    userRepository.read(userId)?.let { user ->
                        if (commandName == Command.CANCEL.name) {
                            val currentCommand = currentCommandMap[user.id] ?: return@message

                            currentCharacterMap.remove(user.id)
                            currentGameMap.remove(user.id)
                            currentCommandMap.remove(user.id)

                            bot.sendMessage(
                                chatId = ChatId.fromId(user.id),
                                text = intl.translate(
                                    id = "command.cancel.message",
                                    value = "commandName" to currentCommand.name.lowercase()
                                ),
                                replyMarkup = ReplyKeyboardRemove()
                            )

                            return@message
                        }

                        if ((commandName == Command.NEWGAME.name ||
                            commandName == Command.DELETEGAME.name ||
                            commandName == Command.NEWCHAR.name ||
                            commandName == Command.NEWCHARRANK.name) && user.role == User.Role.EDITOR) {
                            throw User.UnauthorizedError()
                        }

                        if (currentCommandMap[user.id] != null) {
                            bot.sendMessage(
                                chatId = ChatId.fromId(user.id),
                                text = "\u2062",
                                replyMarkup = ReplyKeyboardRemove()
                            ).getOrNull()?.let { message ->
                                bot.deleteMessage(chatId = ChatId.fromId(user.id), messageId = message.messageId)
                            }

                            currentCharacterMap.remove(user.id)
                            currentGameMap.remove(user.id)
                            currentCommandMap.remove(user.id)
                        }

                        if (commandName == Command.NEWGAME.name) {
                            currentCommandMap[user.id] = Command.valueOf(commandName)
                            currentGameMap[user.id] = Game()

                            bot.sendMessage(
                                chatId = ChatId.fromId(user.id),
                                text = intl.translate(id = "command.newgame.name.message")
                            )

                            return@message
                        }

                        val games = gameRepository.read().filter { game ->
                            val characters = characterRepository.read(game.id)

                            when (commandName) {
                                Command.DELETEGAME.name -> characters.isEmpty()
                                Command.EDITCHAR.name -> characters.any { character -> !character.isRanking }
                                Command.EDITCHARRANK.name -> characters.any { character -> character.isRanking }
                                else -> true
                            }
                        }.sortedBy { game -> game.name.value }

                        if (games.isEmpty()) {
                            bot.sendMessage(
                                chatId = ChatId.fromId(user.id),
                                text = intl.translate(id = "command.game.list.empty.message")
                            )
                        } else {
                            currentCommandMap[user.id] = Command.valueOf(commandName)

                            bot.sendMessage(
                                chatId = ChatId.fromId(user.id),
                                text = intl.translate(id = "command.game.list.message"),
                                replyMarkup = if (commandName == Command.SETGAMESUB.name) {
                                    val userGameSubscriptions = userGameSubscriptionRepository.read()

                                    UserGameSubscriptionInlineKeyboardMarkup.create(games, userGameSubscriptions)
                                } else GameInlineKeyboardMarkup.create(games)
                            )
                        }
                    } ?: throw User.UnauthorizedError()
                }.onFailure { error ->
                    when (error) {
                        is User.UnauthorizedError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.user.unauthorized.message")
                        )
                        else -> {
                            log.info(error.stackTraceToString())

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = "command.error.message")
                            )
                        }
                    }
                }
            }

            message(Filter.Text) {
                val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = message.chat.id

                val currentCommand = currentCommandMap[userId] ?: return@message

                var currentGame = currentGameMap[userId] ?: return@message

                runCatching {
                    if (currentGame.name.value.isEmpty() && currentCommand == Command.NEWGAME) {
                        val gameName = Game.Name(message.text!!)

                        val gameExists = gameRepository.read().any { game ->
                            game.name.value.normalize().equals(gameName.value.normalize(), true)
                        }

                        if (gameExists) {
                            throw Game.Name.ExistsError()
                        }

                        currentGame = Game(name = gameName)

                        gameRepository.create(currentGame)

                        bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(
                                id = "command.newgame.success.message",
                                value = "gameName" to currentGame.name.value
                            )
                        )

                        currentGameMap.remove(userId)
                        currentCommandMap.remove(userId)

                        return@message
                    }
                }.onFailure { error ->
                    when (error) {
                        is Game.Name.BlankError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.newgame.name.blank.message")
                        )
                        is Game.Name.LengthError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.newgame.name.length.message")
                        )
                        is Game.Name.InvalidError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.newgame.name.invalid.message")
                        )
                        is Game.Name.ExistsError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.newgame.name.exists.message")
                        )
                        else -> {
                            log.info(error.stackTraceToString())

                            currentGameMap.remove(userId)
                            currentCommandMap.remove(userId)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate("command.error.message"),
                                replyMarkup = ReplyKeyboardRemove()
                            )
                        }
                    }
                }

                var currentCharacter = currentCharacterMap[userId] ?: return@message

                runCatching {
                    if (currentCharacter.name.value.isEmpty()) {
                        val characterName = Character.Name(message.text!!)

                        when (currentCommand) {
                            Command.NEWCHAR, Command.NEWCHARRANK -> {
                                val characterExists = currentGame.characters.any { character ->
                                    character.name.value.normalize().equals(characterName.value.normalize(), true)
                                }

                                if (characterExists) {
                                    throw Character.Name.ExistsError()
                                }

                                currentCharacterMap[userId] = Character(
                                    id = currentCharacter.id,
                                    name = characterName,
                                    isRanking = currentCharacter.isRanking,
                                    game = currentCharacter.game
                                )

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = if (currentCharacter.isRanking) {
                                        "command.newcharrank.content.message"
                                    } else "command.newchar.content.message")
                                )
                            }
                            Command.EDITCHAR, Command.EDITCHARRANK -> {
                                val character = currentGame.characters.firstOrNull { character ->
                                    character.name.value == characterName.value
                                } ?: return@message

                                currentCharacterMap[userId] = Character(
                                    id = character.id,
                                    name = character.name,
                                    isRanking = character.isRanking,
                                    game = currentCharacter.game
                                )

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = if (character.isRanking) {
                                        "command.editcharrank.content.message1"
                                    } else "command.editchar.content.message1"),
                                    replyMarkup = ReplyKeyboardRemove()
                                )

                                bot.sendMessage(chatId = ChatId.fromId(userId), text = character.content.value)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = if (character.isRanking) {
                                        "command.editcharrank.content.message2"
                                    } else "command.editchar.content.message2")
                                )
                            }
                            else -> return@message
                        }
                    } else {
                        if (currentCharacter.content.value.isNotEmpty()) return@message

                        when (currentCommand) {
                            Command.NEWCHAR, Command.EDITCHAR -> {
                                val characterContent = Character.Content(message.text!!)

                                currentCharacter = Character(
                                    id = currentCharacter.id,
                                    name = currentCharacter.name,
                                    content = characterContent,
                                    isRanking = currentCharacter.isRanking,
                                    game = currentCharacter.game
                                )

                                val characterImageName = currentCharacter.name.toFileName()
                                val characterImageBytes = currentCharacter.renderToImage(characterTemplatePath, width = 2048)

                                val currentCharacterImage = CharacterImage(
                                    name = characterImageName,
                                    bytes = characterImageBytes,
                                    characterId = currentCharacter.id
                                ).apply {
                                    id = bot.sendDocumentAndGetFileId(
                                        chatId = ChatId.fromChannelUsername(channelUsername),
                                        document = TelegramFile.ByByteArray(bytes, "$name.$type")
                                    )
                                }

                                if (currentCommand == Command.NEWCHAR) {
                                    characterRepository.create(currentCharacter)
                                    characterImageRepository.create(currentCharacterImage)
                                } else {
                                    characterRepository.update(currentCharacter)
                                    characterImageRepository.read(currentCharacter.id)?.let { characterImage ->
                                        characterImageRepository.delete(characterImage.id)
                                        characterImageRepository.create(currentCharacterImage)
                                    }
                                }

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = if (currentCommand == Command.NEWCHAR) {
                                            "command.newchar.success.message"
                                        } else "command.editchar.success.message",
                                        value = "characterName" to currentCharacter.name.value
                                    )
                                )

                                val currentUser = userRepository.read(userId)!!

                                userGameSubscriptionRepository.read(currentGame.id)
                                    .filter { userGameSubscription -> userGameSubscription.userId != currentUser.id }
                                    .forEach { userGameSubscription ->
                                        val userIntl = bot.getChatMember(
                                            chatId = ChatId.fromId(userGameSubscription.userId),
                                            userId = userGameSubscription.userId
                                        ).getOrNull()?.user?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                                        bot.sendMessage(
                                            chatId = ChatId.fromId(userGameSubscription.userId),
                                            text = userIntl.translate(
                                                id = if (currentCommand == Command.NEWCHAR) {
                                                    "command.setgamesub.character.created.message"
                                                } else "command.setgamesub.character.edited.message",
                                                values = listOf(
                                                    "userName" to currentUser.name,
                                                    "characterName" to currentCharacter.name.value
                                                )
                                            )
                                        )
                                    }

                                currentCharacterMap.remove(userId)
                                currentGameMap.remove(userId)
                                currentCommandMap.remove(userId)
                            }
                            Command.NEWCHARRANK, Command.EDITCHARRANK -> {
                                val characterContent = Character.Content(message.text!!)

                                currentCharacterMap[userId] = Character(
                                    id = currentCharacter.id,
                                    name = currentCharacter.name,
                                    content = characterContent,
                                    isRanking = currentCharacter.isRanking,
                                    game = currentCharacter.game
                                )

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = if (currentCommand == Command.NEWCHARRANK) {
                                        "command.newcharrank.content.image.message"
                                    } else "command.editcharrank.content.image.message")
                                )
                            }
                            else -> return@message
                        }
                    }
                }.onFailure { error ->
                    when (error) {
                        is Character.Name.BlankError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = if (currentCharacter.isRanking) {
                                "command.newcharrank.name.blank.message"
                            } else "command.newchar.name.blank.message")
                        )
                        is Character.Name.LengthError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = if (currentCharacter.isRanking) {
                                "command.newcharrank.name.length.message"
                            } else "command.newchar.name.length.message")
                        )
                        is Character.Name.InvalidError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = if (currentCharacter.isRanking) {
                                "command.newcharrank.name.invalid.message"
                            } else "command.newchar.name.invalid.message")
                        )
                        is Character.Name.ExistsError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = if (currentCharacter.isRanking) {
                                "command.newcharrank.name.exists.message"
                            } else "command.newchar.name.exists.message")
                        )
                        is Character.Content.BlankError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = if (currentCharacter.isRanking) {
                                "command.newcharrank.content.blank.message"
                            } else "command.newchar.content.blank.message")
                        )
                        is Character.Content.LengthError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = if (currentCharacter.isRanking) {
                                "command.newcharrank.content.length.message"
                            } else "command.newchar.content.length.message")
                        )
                        else -> {
                            log.info(error.stackTraceToString())

                            currentCharacterMap.remove(userId)
                            currentGameMap.remove(userId)
                            currentCommandMap.remove(userId)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate("command.error.message"),
                                replyMarkup = ReplyKeyboardRemove()
                            )
                        }
                    }
                }
            }

            message(Filter.Photo) {
                val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = message.chat.id

                val currentCommand = currentCommandMap[userId] ?: return@message
                val currentGame = currentGameMap[userId] ?: return@message

                var currentCharacter = currentCharacterMap[userId] ?: return@message

                if (currentCharacter.content.value.isEmpty()) return@message

                runCatching {
                    when (currentCommand) {
                        Command.NEWCHARRANK, Command.EDITCHARRANK -> {
                            val characterContent = Character.Content(
                                currentCharacter.content.value,
                                bot.getAndCreateTempFile(message.photo!!.last().fileId).absolutePath
                            )

                            currentCharacter = Character(
                                id = currentCharacter.id,
                                name = currentCharacter.name,
                                content = characterContent,
                                isRanking = currentCharacter.isRanking,
                                game = currentCharacter.game
                            )

                            val characterImageName = currentCharacter.name.toFileName()
                            val characterImageBytes = currentCharacter.renderToImage(characterTemplatePath, width = 2048)

                            val currentCharacterImage = CharacterImage(
                                name = characterImageName,
                                bytes = characterImageBytes,
                                characterId = currentCharacter.id
                            ).apply {
                                id = bot.sendDocumentAndGetFileId(
                                    chatId = ChatId.fromChannelUsername(channelUsername),
                                    document = TelegramFile.ByByteArray(bytes, "$name.$type")
                                )
                            }

                            if (currentCommand == Command.NEWCHARRANK) {
                                characterRepository.create(currentCharacter)
                                characterImageRepository.create(currentCharacterImage)
                            } else {
                                characterRepository.update(currentCharacter)
                                characterImageRepository.read(currentCharacter.id)?.let { characterImage ->
                                    characterImageRepository.delete(characterImage.id)
                                    characterImageRepository.create(currentCharacterImage)
                                }
                            }

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(
                                    id = if (currentCommand == Command.NEWCHARRANK) {
                                        "command.newcharrank.success.message"
                                    } else "command.editcharrank.success.message",
                                    value = "characterName" to currentCharacter.name.value
                                )
                            )

                            val currentUser = userRepository.read(userId)!!

                            userGameSubscriptionRepository.read(currentGame.id)
                                .filter { userGameSubscription -> userGameSubscription.userId != currentUser.id }
                                .forEach { userGameSubscription ->
                                    val userIntl = bot.getChatMember(
                                        chatId = ChatId.fromId(userGameSubscription.userId),
                                        userId = userGameSubscription.userId
                                    ).getOrNull()?.user?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(userGameSubscription.userId),
                                        text = userIntl.translate(
                                            id = if (currentCommand == Command.NEWCHARRANK) {
                                                "command.setgamesub.character.ranking.created.message"
                                            } else "command.setgamesub.character.ranking.edited.message",
                                            values = listOf(
                                                "userName" to currentUser.name,
                                                "characterName" to currentCharacter.name.value
                                            )
                                        )
                                    )
                                }
                        }
                        else -> return@message
                    }

                    currentGameMap.remove(userId)
                    currentCommandMap.remove(userId)
                }.onFailure { error ->
                    log.info(error.stackTraceToString())

                    currentCharacterMap.remove(userId)
                    currentGameMap.remove(userId)
                    currentCommandMap.remove(userId)

                    bot.sendMessage(
                        chatId = ChatId.fromId(userId),
                        text = intl.translate(id = "command.error.message"),
                        replyMarkup = ReplyKeyboardRemove()
                    )
                }
            }

            callbackQuery {
                val intl = callbackQuery.from.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = callbackQuery.message?.chat?.id ?: return@callbackQuery

                val currentCommand = currentCommandMap[userId] ?: return@callbackQuery

                runCatching {
                    val game = gameRepository.read(UUID.fromString(callbackQuery.data))!!

                    when (currentCommand) {
                        Command.DELETEGAME -> {
                            userGameSubscriptionRepository.delete(game.id)
                            gameRepository.delete(game.id)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(
                                    id = "command.deletegame.success.message",
                                    value = "gameName" to game.name.value
                                )
                            )

                            currentCommandMap.remove(userId)
                        }
                        Command.SETGAMESUB -> {
                            userGameSubscriptionRepository.read(userId, game.id)?.run {
                                userGameSubscriptionRepository.delete(userId, game.id)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.setgamesub.unsubscribe.success.message",
                                        value = "gameName" to game.name.value
                                    )
                                )
                            } ?: run {
                                val userGameSubscription = UserGameSubscription(userId, game.id)

                                userGameSubscriptionRepository.create(userGameSubscription)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.setgamesub.subscribe.success.message",
                                        value = "gameName" to game.name.value
                                    )
                                )
                            }

                            currentCommandMap.remove(userId)
                        }
                        Command.NEWCHAR, Command.NEWCHARRANK -> {
                            val characterIsRanking = currentCommand != Command.NEWCHAR

                            game.characters = characterRepository.read(game.id).filter { character ->
                                character.isRanking == characterIsRanking
                            }

                            currentGameMap[userId] = game
                            currentCharacterMap[userId] = Character(isRanking = characterIsRanking, game = game)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = if (characterIsRanking) {
                                    "command.newcharrank.name.message"
                                } else "command.newchar.name.message")
                            )
                        }
                        Command.EDITCHAR, Command.EDITCHARRANK -> {
                            val characterIsRanking = currentCommand != Command.EDITCHAR

                            game.characters = characterRepository.read(game.id).filter { character ->
                                character.isRanking == characterIsRanking
                            }.sortedBy { character -> character.name.value }

                            currentGameMap[userId] = game
                            currentCharacterMap[userId] = Character(isRanking = characterIsRanking, game = game)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = if (characterIsRanking) {
                                    "command.editcharrank.character.list.message"
                                } else "command.editchar.character.list.message"),
                                replyMarkup = CharacterKeyboardReplyMarkup.create(game.characters)
                            )
                        }
                        else -> return@callbackQuery
                    }
                }.onFailure { error ->
                    log.info(error.stackTraceToString())

                    currentCharacterMap.remove(userId)
                    currentGameMap.remove(userId)
                    currentCommandMap.remove(userId)

                    bot.sendMessage(
                        chatId = ChatId.fromId(userId),
                        text = intl.translate(id = "command.error.message")
                    )
                }
            }

            inlineQuery {
                val characterNameQuery = inlineQuery.query

                val characterInlineQueryResults = if (characterNameQuery.isEmpty()) {
                    emptyList()
                } else if (characterNameQuery.length < 3) {
                    emptyList()
                } else {
                    try {
                        val characterName = Character.Name(characterNameQuery)

                        characterRepository.read().filter { character ->
                            character.name.value.normalize().contains(characterName.value.normalize(), true)
                        }.mapNotNull { character ->
                            characterImageRepository.read(character.id)?.let { characterImage ->
                                InlineQueryResult.CachedDocument(
                                    id = character.id.toString(),
                                    title = character.name.value,
                                    documentFileId = characterImage.id,
                                    description = gameRepository.read(character.game!!.id)!!.name.value
                                )
                            }
                        }
                    } catch (_: Error) {
                        emptyList()
                    }
                }

                bot.answerInlineQuery(
                    inlineQueryId = inlineQuery.id,
                    cacheTime = 0,
                    inlineQueryResults = characterInlineQueryResults
                )
            }

            telegramError { log.info(error.getErrorMessage()) }
        }
    }

    bot.startPolling()
}

fun Application.api(userRepository: UserRepository) {
    install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }

    routing {
        get("/users") {
            val users = userRepository.read()

            val response = buildJsonObject {
                put("ok", true)
                put("result", buildJsonArray {
                    users.map { user ->
                        add(buildJsonObject {
                            put("id", user.id)
                            put("name", user.name)
                            put("role", user.role.name.lowercase())
                        })
                    }
                })
            }

            call.respond(response)
        }
        post("/users") {
            val body = call.receive<JsonObject>()

            val users = mutableListOf<JsonObject>()

            body["users"]!!.jsonArray.forEach { jsonElement ->
                val userId = jsonElement.jsonObject["id"]!!.jsonPrimitive.content.toLong()
                val userName = jsonElement.jsonObject["name"]!!.jsonPrimitive.content
                val userRole = jsonElement.jsonObject["role"]!!.jsonPrimitive.content.uppercase()

                val user = User(id = userId, name = userName, role = User.Role.valueOf(userRole))

                val userExists = userRepository.read(user.id) != null

                if (userExists) userRepository.update(user)
                else userRepository.create(user)

                users.add(buildJsonObject {
                    put("id", user.id)
                    put("name", user.name)
                    put("role", user.role.name.lowercase())
                    put(if (userExists) "updated" else "created", true)
                })
            }

            val response = buildJsonObject {
                put("ok", true)
                put("result", buildJsonArray {
                    users.map { user -> add(user) }
                })
            }

            call.respond(response)
        }
        delete("/users") {
            val body = call.receive<JsonObject>()

            val users = mutableListOf<User>()

            body["users"]!!.jsonArray.forEach { jsonElement ->
                val userId = jsonElement.jsonObject["id"]!!.jsonPrimitive.content.toLong()

                val user = userRepository.read(userId)

                if (user != null && user.role != User.Role.ADMIN) {
                    userRepository.delete(user.id)

                    users.add(user)
                }
            }

            val response = buildJsonObject {
                put("ok", true)
                put("result", buildJsonArray {
                    users.map { user ->
                        add(buildJsonObject {
                            put("id", user.id)
                            put("name", user.name)
                            put("role", user.role.name.lowercase())
                        })
                    }
                })
            }

            call.respond(response)
        }
    }
}

fun main() {
    val appConfig = HoconApplicationConfig(ConfigFactory.load())

    val database = Database.create(appConfig.config("database"))

    val userRepository = UserRepository(database)
    val gameRepository = GameRepository(database)
    val userGameSubscriptionRepository = UserGameSubscriptionRepository(database)
    val characterRepository = CharacterRepository(database)
    val characterImageRepository = CharacterImageRepository(database)

    embeddedServer(
        factory = io.ktor.server.cio.CIO,
        environment = applicationEnvironment { config = appConfig },
        configure = {
            connector {
                host = appConfig.property("server.host").getString()
                port = appConfig.property("server.port").getString().toInt()
            }
        },
        module = {
            bot(
                userRepository,
                gameRepository,
                userGameSubscriptionRepository,
                characterRepository,
                characterImageRepository
            )
            api(userRepository)
        }
    ).start(wait = true)
}