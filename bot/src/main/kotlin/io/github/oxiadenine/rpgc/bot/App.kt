package io.github.oxiadenine.rpgc.bot

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
import com.typesafe.config.Config
import io.github.oxiadenine.rpgc.bot.command.*
import io.github.oxiadenine.rpgc.bot.graphic.CharacterImageRenderer
import io.github.oxiadenine.rpgc.common.normalize
import io.github.oxiadenine.rpgc.common.repository.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue

class Application private constructor(val config: Config) {
    val log: Logger = LoggerFactory.getLogger(Application::class.java)

    companion object {
        fun create(config: Config, init: Application.() -> Unit) = Application(config).apply { init() }
    }
}

fun Application.bot(
    userRepository: UserRepository,
    gameRepository: GameRepository,
    userGameSubscriptionRepository: UserGameSubscriptionRepository,
    characterRepository: CharacterRepository,
    characterImageRepository: CharacterImageRepository
) {
    val characterImageTemplatePath = config.getString("bot.character.templatePath")
    val characterImageChannelUsername = config.getString("telegram.channelUsername")

    val characterImageRenderer = CharacterImageRenderer(characterImageTemplatePath)

    val bot = com.github.kotlintelegrambot.bot {
        token = config.getString("telegram.token")

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
                    Command.Name.NEW_CHARACTER.text, Command.Name.NEW_CHARACTER_RANKING.text -> {
                        NewCharacterCommandHandler(
                            commandContext,
                            userRepository,
                            gameRepository,
                            userGameSubscriptionRepository,
                            characterRepository,
                            characterImageRepository,
                            characterImageRenderer,
                            characterImageChannelUsername
                        )
                    }
                    Command.Name.EDIT_CHARACTER.text, Command.Name.EDIT_CHARACTER_RANKING.text -> {
                        EditCharacterCommandHandler(
                            commandContext,
                            userRepository,
                            gameRepository,
                            userGameSubscriptionRepository,
                            characterRepository,
                            characterImageRepository,
                            characterImageRenderer,
                            characterImageChannelUsername
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