package io.github.oxiadenine.rpgc

import com.github.kotlintelegrambot.Bot
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
import io.github.oxiadenine.rpgc.command.*
import io.github.oxiadenine.rpgc.repository.*
import io.github.oxiadenine.rpgc.repository.Character
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentLinkedQueue

fun Application.bot(
    userRepository: UserRepository,
    gameRepository: GameRepository,
    userGameSubscriptionRepository: UserGameSubscriptionRepository,
    characterRepository: CharacterRepository,
    characterImageRepository: CharacterImageRepository
) {
    val appConfig = environment.config

    val characterTemplatePath = appConfig.property("bot.character.templatePath").getString()
    val channelUsername = appConfig.property("telegram.channelUsername").getString()

    val bot = com.github.kotlintelegrambot.bot {
        token = appConfig.property("telegram.token").getString()

        val commandHandlers = ConcurrentLinkedQueue<CommandHandler>()

        dispatch {
            message(Filter.Command) {
                val commandContext = CommandContext(bot, message)

                if (commandContext.command.name != Command.Name.START.text) {
                    userRepository.read(message.chat.id)?.let { currentUser ->
                        commandContext.user = currentUser
                    }
                }

                if (commandContext.command.name != Command.Name.CANCEL.text) {
                    commandHandlers.firstOrNull { commandHandler ->
                        commandHandler.context.user.id == commandContext.user.id
                    }?.let { commandHandler ->
                        commandContext.bot.sendMessage(
                            chatId = ChatId.fromId(commandContext.user.id),
                            text = commandContext.intl.translate(
                                id = "command.cancel.warning.message",
                                value = "commandName" to commandHandler.context.command.name
                            )
                        )

                        return@message
                    }
                }

                val commandHandler = when (commandContext.command.name) {
                    Command.Name.START.text -> StartCommandHandler(commandContext, userRepository)
                    Command.Name.SET_LANGUAGE.text -> SetLanguageCommandHandler(commandContext, userRepository)
                    Command.Name.NEW_GAME.text -> NewGameCommandHandler(commandContext, gameRepository)
                    Command.Name.DELETE_GAME.text -> DeleteGameCommandHandler(
                        commandContext, gameRepository, userGameSubscriptionRepository, characterRepository
                    )
                    Command.Name.SET_GAME_SUBSCRIPTION.text -> SetGameSubscriptionCommandHandler(
                        commandContext, gameRepository, userGameSubscriptionRepository
                    )
                    Command.Name.NEW_CHARACTER.text,Command.Name.NEW_CHARACTER_RANKING.text -> {
                        NewCharacterCommandHandler(
                            commandContext,
                            userRepository,
                            gameRepository,
                            userGameSubscriptionRepository,
                            characterRepository,
                            characterImageRepository,
                            characterTemplatePath,
                            channelUsername
                        )
                    }
                    Command.Name.EDIT_CHARACTER.text,Command.Name.EDIT_CHARACTER_RANKING.text -> {
                        EditCharacterCommandHandler(
                            commandContext,
                            userRepository,
                            gameRepository,
                            userGameSubscriptionRepository,
                            characterRepository,
                            characterImageRepository,
                            characterTemplatePath,
                            channelUsername
                        )
                    }
                    Command.Name.CANCEL.text -> CancelCommandHandler(commandContext, commandHandlers)
                    else -> CharacterImageCommandHandler(
                        commandContext, gameRepository, characterRepository, characterImageRepository
                    )
                }

                commandHandlers.add(commandHandler)

                runCatching {
                    commandHandler.handle()

                    if (commandHandler.isHandled) {
                        commandHandlers.remove(commandHandler)
                    }
                }.onFailure { exception ->
                    commandHandlers.remove(commandHandler)

                    log.info(exception.stackTraceToString())

                    commandContext.bot.sendMessage(
                        chatId = ChatId.fromId(commandContext.user.id),
                        text = commandContext.intl.translate(id = "command.error.message")
                    )
                }
            }

            message(Filter.Text) {
                val commandHandler = commandHandlers.firstOrNull { command ->
                    command.context.user.id == message.chat.id
                } ?: return@message

                val commandContext = commandHandler.context

                if (
                    commandContext.command.name == Command.Name.SET_LANGUAGE.text ||
                    commandContext.command.name == Command.Name.DELETE_GAME.text ||
                    commandContext.command.name == Command.Name.SET_GAME_SUBSCRIPTION.text
                ) return@message

                val commandMessage = CommandMessage(CommandMessage.Type.TEXT, message.text!!)

                runCatching {
                    commandHandler.handle(commandMessage)

                    if (commandHandler.isHandled) {
                        commandHandlers.remove(commandHandler)
                    }
                }.onFailure { exception ->
                    commandHandlers.remove(commandHandler)

                    log.info(exception.stackTraceToString())

                    commandContext.bot.sendMessage(
                        chatId = ChatId.fromId(commandContext.user.id),
                        text = commandContext.intl.translate("command.error.message"),
                        replyMarkup = ReplyKeyboardRemove()
                    )
                }
            }

            message(Filter.Photo) {
                val commandHandler = commandHandlers.firstOrNull { command ->
                    command.context.user.id == message.chat.id
                } ?: return@message

                val commandContext = commandHandler.context

                if (
                    commandContext.command.name != Command.Name.NEW_CHARACTER_RANKING.text &&
                    commandContext.command.name != Command.Name.EDIT_CHARACTER_RANKING.text
                ) return@message

                val commandMessage = CommandMessage(CommandMessage.Type.IMAGE, message.photo!!.last().fileId)

                runCatching {
                    commandHandler.handle(commandMessage)

                    if (commandHandler.isHandled) {
                        commandHandlers.remove(commandHandler)
                    }
                }.onFailure { exception ->
                    commandHandlers.remove(commandHandler)

                    log.info(exception.stackTraceToString())

                    commandContext.bot.sendMessage(
                        chatId = ChatId.fromId(commandContext.user.id),
                        text = commandContext.intl.translate(id = "command.error.message"),
                        replyMarkup = ReplyKeyboardRemove()
                    )
                }
            }

            callbackQuery {
                val userId = callbackQuery.message?.chat?.id ?: return@callbackQuery

                val commandHandler = commandHandlers.firstOrNull { command ->
                    command.context.user.id == userId
                } ?: return@callbackQuery

                val commandContext = commandHandler.context

                if (commandContext.command.name == Command.Name.NEW_GAME.text) {
                    return@callbackQuery
                }

                val commandMessage = CommandMessage(CommandMessage.Type.QUERY, callbackQuery.data)

                runCatching {
                    commandHandler.handle(commandMessage)

                    if (commandHandler.isHandled) {
                        commandHandlers.remove(commandHandler)
                    }
                }.onFailure { exception ->
                    commandHandlers.remove(commandHandler)

                    log.info(exception.stackTraceToString())

                    commandContext.bot.sendMessage(
                        chatId = ChatId.fromId(commandContext.user.id),
                        text = commandContext.intl.translate(id = "command.error.message")
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
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                bot.answerInlineQuery(
                    inlineQueryId = inlineQuery.id,
                    cacheTime = 0,
                    inlineQueryResults = characterInlineQueryResults
                )
            }

            telegramError {
                commandHandlers.clear()

                log.info(error.getErrorMessage())
            }
        }
    }

    bot.startPolling()
}

fun Bot.getAndCreateTempFile(fileId: String) = this.getFile(fileId).let { result ->
    val telegramFile = result.first?.let { response ->
        if (response.isSuccessful) {
            response.body()!!.result!!.filePath!!
        } else error(response.message())
    }?.let { filePath ->
        this.downloadFile(filePath).first?.let { response ->
            if (response.isSuccessful) {
                TelegramFile.ByByteArray(
                    fileBytes = response.body()!!.bytes(),
                    filename = filePath.substringAfterLast("/")
                )
            } else error(response.message())
        } ?: throw result.second!!
    } ?: throw result.second!!

    val fileExtension = telegramFile.filename!!.substringAfter(".")

    val tempFile = kotlin.io.path.createTempFile(suffix = ".$fileExtension").toFile()
    tempFile.writeBytes(telegramFile.fileBytes)

    tempFile!!
}

fun Bot.sendDocumentAndGetFileId(chatId: ChatId, document: TelegramFile) = this.sendDocument(chatId, document).let { result ->
    result.first?.let { response ->
        if (response.isSuccessful) {
            response.body()!!.result!!.document!!.fileId
        } else error(response.message())
    } ?: throw result.second!!
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
                            put("language", user.language)
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
                val id = jsonElement.jsonObject["id"]!!.jsonPrimitive.content.toLong()
                val name = jsonElement.jsonObject["name"]!!.jsonPrimitive.content
                val role = jsonElement.jsonObject["role"]?.jsonPrimitive?.content?.uppercase()?.let { role ->
                    User.Role.valueOf(role)
                } ?: User.Role.EDITOR
                val language = jsonElement.jsonObject["language"]?.jsonPrimitive?.content ?: Intl.DEFAULT_LANGUAGE

                val user = User(id, name, role, language)

                val userExists = userRepository.read(user.id) != null

                if (userExists) {
                    userRepository.update(user)
                } else userRepository.create(user)

                users.add(buildJsonObject {
                    put("id", user.id)
                    put("name", user.name)
                    put("role", user.role.name.lowercase())
                    put("language", user.language)
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
                            put("language", user.language)
                        })
                    }
                })
            }

            call.respond(response)
        }
    }
}