package io.github.oxiadenine.rpgcbot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.*
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ReplyKeyboardRemove
import com.github.kotlintelegrambot.extensions.filters.Filter
import io.github.oxiadenine.rpgcbot.network.TelegraphApi
import io.github.oxiadenine.rpgcbot.view.CharacterKeyboardReplyMarkup
import io.github.oxiadenine.rpgcbot.view.GameInlineKeyboardMarkup
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.resources.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

enum class Command {
    START,
    NEW_CHAR_PAGE,
    EDIT_CHAR_PAGE,
    CANCEL;

    override fun toString() = name.replace("_", "").lowercase()
}

data class Game(val code: String, val name: String)
data class Character(var id: String = "", var name: String = "", var description: String = "")

@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val telegramBotToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: ""
    val userIdWhitelist = System.getenv("USER_ID_WHITELIST") ?: ""
    val gameList = System.getenv("GAME_NAME_LIST") ?: ""
    val telegraphUsername = System.getenv("TELEGRAPH_USERNAME") ?: ""
    val telegraphAccessToken = System.getenv("TELEGRAPH_ACCESS_TOKEN") ?: ""

    val rpgcBot = bot {
        token = telegramBotToken

        val userIds = userIdWhitelist.split(",").map { userId -> userId.toLong() }
        val games = gameList.split(",").map { gameName -> Game(
            code = gameName.lowercase().split(" ").joinToString("") { "${it[0]}" },
            name = gameName
        )}

        val telegraphApi = TelegraphApi(HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { explicitNulls = false })
            }
            install(Resources)
            install(DefaultRequest) {
                url("https://api.telegra.ph/")
            }

            expectSuccess = true
        })

        val currentCommandMap = ConcurrentHashMap<Long, Command>()
        val currentGameMap = ConcurrentHashMap<Long, Game>()
        val currentCharacterMap = ConcurrentHashMap<Long, Character>()

        dispatch {
            command(Command.START.toString()) {
                val userId = message.chat.id

                val intl = Intl(message.from?.languageCode ?: Intl.DEFAULT_LOCALE)

                bot.sendMessage(chatId = ChatId.fromId(userId), text = intl.translate(id = "command.start.message"))
            }

            Command.entries.drop(1).dropLast(1).map { command ->
                command(command.toString()) {
                    val userId = message.chat.id

                    val intl = Intl(message.from?.languageCode ?: Intl.DEFAULT_LOCALE)

                    bot.sendMessage(
                        chatId = ChatId.fromId(userId),
                        text = "\u2062",
                        replyMarkup = ReplyKeyboardRemove()
                    ).getOrNull()?.let { message ->
                        bot.deleteMessage(chatId = ChatId.fromId(userId), messageId = message.messageId)
                    }

                    currentCharacterMap.remove(userId)
                    currentGameMap.remove(userId)
                    currentCommandMap.remove(userId)

                    if (userIds.contains(userId)) {
                        currentCommandMap[userId] = command

                        bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.game.list.message"),
                            replyMarkup = GameInlineKeyboardMarkup.create(games)
                        )
                    } else {
                        bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.unauthorized.message")
                        )
                    }
                }
            }

            games.map { game ->
                callbackQuery(game.code) {
                    val userId = callbackQuery.message?.chat?.id ?: return@callbackQuery

                    val intl = Intl(callbackQuery.from.languageCode ?: Intl.DEFAULT_LOCALE)

                    val command = currentCommandMap[userId] ?: return@callbackQuery

                    when (command) {
                        Command.NEW_CHAR_PAGE -> {
                            currentGameMap[userId] = game

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = "command.new.character.input.title.message")
                            )
                        }
                        Command.EDIT_CHAR_PAGE -> {
                            telegraphApi.getPageList(TelegraphApi.GetPageList(accessToken = telegraphAccessToken))
                                .onSuccess { pageList ->
                                    val characters = pageList.pages
                                        .filter { page -> page.path.contains(game.code) }
                                        .map { page -> Character(id = page.path, name = page.title) }

                                    if (characters.isEmpty()) {
                                        bot.sendMessage(
                                            chatId = ChatId.fromId(userId),
                                            text = intl.translate(id = "command.game.list.empty.message")
                                        )
                                    } else {
                                        currentGameMap[userId] = game

                                        bot.sendMessage(
                                            chatId = ChatId.fromId(userId),
                                            text = intl.translate(id = "command.edit.character.list.message"),
                                            replyMarkup = CharacterKeyboardReplyMarkup.create(characters)
                                        )
                                    }
                                }.onFailure {
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(userId),
                                        text = intl.translate(id = "command.error.message")
                                    )

                                    currentCharacterMap.remove(userId)
                                    currentGameMap.remove(userId)
                                    currentCommandMap.remove(userId)
                                }
                        }
                        else -> return@callbackQuery
                    }
                }
            }

            message(Filter.Text) {
                val userId = message.chat.id

                val intl = Intl(message.from?.languageCode ?: Intl.DEFAULT_LOCALE)

                val command = currentCommandMap[userId] ?: return@message
                val game = currentGameMap[userId] ?: return@message

                var character = currentCharacterMap[userId]

                if (character != null) {
                    when (command) {
                        Command.NEW_CHAR_PAGE -> {
                            character.description = message.text!!

                            telegraphApi.createPage(TelegraphApi.CreatePage(
                                accessToken = telegraphAccessToken,
                                title = "${game.code}-${character.name.lowercase()}",
                                authorName = telegraphUsername,
                                content = "[\"${character.description}\"]"
                            )).mapCatching { page ->
                                character!!.id = page.path

                                telegraphApi.editPage(TelegraphApi.EditPage(
                                    path = character!!.id,
                                    accessToken = telegraphAccessToken,
                                    title = character!!.name,
                                    content = "[\"${character!!.description}\"]",
                                    authorName = telegraphUsername
                                )).getOrThrow()
                            }.onSuccess {
                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.new.character.success.message",
                                        value = "title" to character!!.name
                                    )
                                )
                            }.onFailure {
                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.error.message")
                                )
                            }
                        }
                        Command.EDIT_CHAR_PAGE -> {
                            character.description = message.text!!

                            telegraphApi.editPage(TelegraphApi.EditPage(
                                path = character.id,
                                accessToken = telegraphAccessToken,
                                title = character.name,
                                content = "[\"${character.description}\"]",
                                authorName = telegraphUsername
                            )).onSuccess {
                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.edit.character.success.message",
                                        value = "title" to character!!.name
                                    )
                                )
                            }.onFailure {
                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.error.message")
                                )
                            }
                        }
                        else -> return@message
                    }

                    currentCharacterMap.remove(userId)
                    currentGameMap.remove(userId)
                    currentCommandMap.remove(userId)
                } else {
                    val characterName = message.text!!.lowercase()

                    when (command) {
                        Command.NEW_CHAR_PAGE -> {
                            telegraphApi.getPageList(TelegraphApi.GetPageList(accessToken = telegraphAccessToken))
                                .onSuccess { pageList ->
                                    if (pageList.pages.none { page ->
                                        page.path.contains("${game.code}-${characterName}")
                                    }) {
                                        character = Character(
                                            name = characterName.split(" ").joinToString(" ") { part ->
                                                part.replaceFirstChar { it.uppercase() }
                                            }
                                        )

                                        currentCharacterMap[userId] = character!!

                                        bot.sendMessage(
                                            chatId = ChatId.fromId(userId),
                                            text = intl.translate(
                                                id = "command.new.character.input.content.message",
                                                value = "title" to character!!.name
                                            )
                                        )
                                    } else {
                                        bot.sendMessage(
                                            chatId = ChatId.fromId(userId),
                                            text = intl.translate("command.new.character.input.title.exists.message")
                                        )
                                    }
                                }.onFailure {
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(userId),
                                        text = intl.translate("command.error.message")
                                    )

                                    currentCharacterMap.remove(userId)
                                    currentGameMap.remove(userId)
                                    currentCommandMap.remove(userId)
                                }
                        }
                        Command.EDIT_CHAR_PAGE -> {
                            telegraphApi.getPageList(TelegraphApi.GetPageList(accessToken = telegraphAccessToken))
                                .mapCatching { pageList ->
                                    val page = pageList.pages.first { page ->
                                        page.path.contains("${game.code}-${characterName}")
                                    }

                                    telegraphApi.getPage(TelegraphApi.GetPage(path = page.path)).getOrThrow()
                                }.onSuccess { page ->
                                    character = Character(
                                        id = page.path,
                                        name = page.title,
                                        description = page.content!![0]
                                    )

                                    currentCharacterMap[userId] = character!!

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(userId),
                                        text = intl.translate(
                                            id = "command.edit.character.input.content.message1",
                                            value = "title" to character!!.name
                                        ),
                                        replyMarkup = ReplyKeyboardRemove()
                                    )

                                    bot.sendMessage(chatId = ChatId.fromId(userId), text = character!!.description)

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(userId),
                                        text = intl.translate(id = "command.edit.character.input.content.message2")
                                    )
                                }.onFailure {
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(userId),
                                        text = intl.translate(id = "command.error.message"),
                                        replyMarkup = ReplyKeyboardRemove()
                                    )

                                    currentCharacterMap.remove(userId)
                                    currentGameMap.remove(userId)
                                    currentCommandMap.remove(userId)
                                }
                        }
                        else -> return@message
                    }
                }
            }

            command(Command.CANCEL.toString()) {
                val userId = message.chat.id

                val intl = Intl(message.from?.languageCode ?: Intl.DEFAULT_LOCALE)

                val command = currentCommandMap[userId] ?: return@command

                currentCharacterMap.remove(userId)
                currentGameMap.remove(userId)
                currentCommandMap.remove(userId)

                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = intl.translate(id = "command.cancel.message", value = "command" to command.toString()),
                    replyMarkup = ReplyKeyboardRemove()
                )
            }
        }
    }

    rpgcBot.startPolling()
}