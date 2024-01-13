package io.github.oxiadenine.rpgcbot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.*
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ReplyKeyboardRemove
import com.github.kotlintelegrambot.entities.inlinequeryresults.InlineQueryResult
import com.github.kotlintelegrambot.entities.inlinequeryresults.InputMessageContent
import com.github.kotlintelegrambot.extensions.filters.Filter
import io.github.oxiadenine.rpgcbot.network.TelegraphApi
import io.github.oxiadenine.rpgcbot.view.CharacterKeyboardReplyMarkup
import io.github.oxiadenine.rpgcbot.view.GameInlineKeyboardMarkup
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap

enum class Command {
    START,
    NEW_CHAR_PAGE,
    EDIT_CHAR_PAGE,
    CANCEL;

    override fun toString() = name.replace("_", "").lowercase()
}

data class Game(val code: String, val name: String)

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
                json(Json {
                    explicitNulls = false
                    encodeDefaults = true
                })
            }
            install(DefaultRequest) {
                url("https://api.telegra.ph/")

                contentType(ContentType.Application.Json)
            }

            expectSuccess = true
        })

        val currentCommandMap = ConcurrentHashMap<Long, Command>()
        val currentGameMap = ConcurrentHashMap<Long, Game>()
        val currentCharacterMap = ConcurrentHashMap<Long, Character>()

        dispatch {
            command(Command.START.toString()) {
                val intl = Intl(message.from?.languageCode ?: Intl.DEFAULT_LOCALE)

                val userId = message.chat.id

                bot.sendMessage(chatId = ChatId.fromId(userId), text = intl.translate(id = "command.start.message"))
            }

            Command.entries.drop(1).dropLast(1).map { command ->
                command(command.toString()) {
                    val intl = Intl(message.from?.languageCode ?: Intl.DEFAULT_LOCALE)

                    val userId = message.chat.id

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
                    val intl = Intl(callbackQuery.from.languageCode ?: Intl.DEFAULT_LOCALE)

                    val userId = callbackQuery.message?.chat?.id ?: return@callbackQuery

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
                                        .map { page ->
                                            Character().apply {
                                                id = page.path
                                                name = page.title
                                            }
                                        }

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
                val intl = Intl(message.from?.languageCode ?: Intl.DEFAULT_LOCALE)

                val userId = message.chat.id

                val command = currentCommandMap[userId] ?: return@message
                val game = currentGameMap[userId] ?: return@message

                var character = currentCharacterMap[userId]

                runCatching {
                    if (character != null) {
                        character!!.description = Character.Description(
                            Jsoup.parse(message.text!!).body().select(">*")
                                .joinToString(",") { element ->
                                    if (element.tagName() == "ol" || element.tagName() == "ul") {
                                        Json.encodeToString(TelegraphApi.Node(
                                            tag = element.tagName(),
                                            children = element.select(">*").map { childElement ->
                                                TelegraphApi.NodeElement(
                                                    tag = childElement.tagName(),
                                                    children = listOf(childElement.text())
                                                )
                                            }
                                        ))
                                    } else {
                                        Json.encodeToString(
                                            TelegraphApi.NodeElement(
                                                tag = element.tagName(),
                                                children = listOf(element.text())
                                            )
                                        )
                                    }
                                }
                        ).value

                        when (command) {
                            Command.NEW_CHAR_PAGE -> {
                                val characterName = character!!.name.lowercase().replace(" ", "-")

                                telegraphApi.createPage(TelegraphApi.CreatePage(
                                    accessToken = telegraphAccessToken,
                                    title = "${game.code}-${characterName}",
                                    authorName = telegraphUsername,
                                    content = "[${character!!.description}]"
                                )).onSuccess { page ->
                                    character!!.id = page.path

                                    telegraphApi.editPage(character!!.id, TelegraphApi.EditPage(
                                        accessToken = telegraphAccessToken,
                                        title = character!!.name,
                                        content = "[${character!!.description}]",
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
                                }
                            }
                            Command.EDIT_CHAR_PAGE -> {
                                telegraphApi.editPage(character!!.id, TelegraphApi.EditPage(
                                    accessToken = telegraphAccessToken,
                                    title = character!!.name,
                                    content = "[${character!!.description}]",
                                    authorName = telegraphUsername
                                )).getOrThrow()

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.edit.character.success.message",
                                        value = "title" to character!!.name
                                    )
                                )
                            }
                            else -> return@message
                        }

                        currentCharacterMap.remove(userId)
                        currentGameMap.remove(userId)
                        currentCommandMap.remove(userId)
                    } else {
                        when (command) {
                            Command.NEW_CHAR_PAGE -> {
                                val characterName = Character.Name(message.text!!).value

                                val pageList = telegraphApi.getPageList(TelegraphApi.GetPageList(
                                    accessToken = telegraphAccessToken
                                )).getOrThrow()

                                val pageExists = pageList.pages
                                    .filter { page -> page.path.contains(game.code) }
                                    .any { page -> page.title.equals(characterName, ignoreCase = true) }

                                if (pageExists) {
                                    throw Character.Name.ExistsException()
                                }

                                character = Character().apply {
                                    name = characterName.lowercase().trim().split(" ").joinToString(" ") { part ->
                                        part.replaceFirstChar { it.uppercase() }
                                    }
                                }

                                currentCharacterMap[userId] = character!!

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.new.character.input.content.message",
                                        value = "title" to character!!.name
                                    )
                                )
                            }
                            Command.EDIT_CHAR_PAGE -> {
                                val characterName = message.text!!

                                val pageList = telegraphApi.getPageList(TelegraphApi.GetPageList(
                                    accessToken = telegraphAccessToken
                                )).getOrThrow()

                                val page = pageList.pages
                                    .filter { page -> page.path.contains(game.code) }
                                    .firstOrNull { page ->
                                        page.title.equals(characterName, ignoreCase = true)
                                    }?.let { page ->
                                        telegraphApi.getPage(page.path, TelegraphApi.GetPage()).getOrThrow()
                                    } ?: return@message

                                val characterDescription = buildString {
                                    page.content!!.map { node ->
                                        append("<${node.jsonObject["tag"]!!.jsonPrimitive.content}>")
                                        node.jsonObject["children"]?.jsonArray?.let { children ->
                                            if (children.first() is JsonObject) appendLine()
                                            children.map { node ->
                                                when (node) {
                                                    is JsonObject -> {
                                                        append("<${node.jsonObject["tag"]!!.jsonPrimitive.content}>")
                                                        node.jsonObject["children"]?.jsonArray?.let { children ->
                                                            children.map { element ->
                                                                append(element.jsonPrimitive.content)
                                                            }
                                                        }
                                                        append("</${node.jsonObject["tag"]!!.jsonPrimitive.content}>")
                                                        appendLine()
                                                    }
                                                    else -> append(node.jsonPrimitive.content)
                                                }
                                            }
                                            append("</${node.jsonObject["tag"]!!.jsonPrimitive.content}>")
                                            appendLine()
                                        } ?: appendLine()
                                    }
                                }

                                character = Character().apply {
                                    id = page.path
                                    name = page.title
                                    description = characterDescription
                                }

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
                            }
                            else -> return@message
                        }
                    }
                }.onFailure { error ->
                    when(error) {
                        is Character.Name.BlankException -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.input.title.blank.message")
                        )
                        is Character.Name.LengthException -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.input.title.length.message")
                        )
                        is Character.Name.InvalidException -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.input.title.invalid.message")
                        )
                        is Character.Name.ExistsException -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.input.title.exists.message")
                        )
                        is Character.Description.BlankException -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.input.content.blank.message")
                        )
                        is Character.Description.LengthException -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.input.content.length.message")
                        )
                        else -> {
                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate("command.error.message"),
                                replyMarkup = ReplyKeyboardRemove()
                            )

                            currentCharacterMap.remove(userId)
                            currentGameMap.remove(userId)
                            currentCommandMap.remove(userId)
                        }
                    }
                }
            }

            command(Command.CANCEL.toString()) {
                val intl = Intl(message.from?.languageCode ?: Intl.DEFAULT_LOCALE)

                val userId = message.chat.id

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

            inlineQuery {
                val intl = Intl(inlineQuery.from.languageCode ?: Intl.DEFAULT_LOCALE)

                val userId = inlineQuery.from.id

                val pageTitleQuery = inlineQuery.query

                if (pageTitleQuery.isBlank() or pageTitleQuery.isEmpty()) return@inlineQuery

                telegraphApi.getPageList(TelegraphApi.GetPageList(accessToken = telegraphAccessToken))
                    .onSuccess { pageList ->
                        val pageInlineQueryResults = pageList.pages
                            .filter { page -> page.title.lowercase().contains(pageTitleQuery.lowercase()) }
                            .map { page -> telegraphApi.getPage(page.path, TelegraphApi.GetPage()).getOrThrow() }
                            .map { page ->
                                InlineQueryResult.Article(
                                    id = page.path,
                                    title = page.title,
                                    inputMessageContent = InputMessageContent.Text(page.url),
                                    description = games.first { game ->
                                        game.code == page.path.substringBefore("-")
                                    }.name
                                )
                            }

                        bot.answerInlineQuery(inlineQuery.id, pageInlineQueryResults)
                    }.onFailure {
                        bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.error.message")
                        )
                    }
            }
        }
    }

    rpgcBot.startPolling()
}