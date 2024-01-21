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
import io.github.oxiadenine.rpgcbot.view.CharacterPageKeyboardReplyMarkup
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
import java.text.Normalizer
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
        val currentCharacterPageMap = ConcurrentHashMap<Long, CharacterPage>()

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

                    currentCharacterPageMap.remove(userId)
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
                                text = intl.translate(id = "command.new.character.page.title.message")
                            )
                        }
                        Command.EDIT_CHAR_PAGE -> {
                            telegraphApi.getPageList(TelegraphApi.GetPageList(accessToken = telegraphAccessToken))
                                .onSuccess { pageList ->
                                    val characterPages = pageList.pages
                                        .filter { page -> page.path.contains(game.code) }
                                        .map { page ->
                                            CharacterPage().apply {
                                                path = page.path
                                                title = page.title
                                            }
                                        }

                                    if (characterPages.isEmpty()) {
                                        bot.sendMessage(
                                            chatId = ChatId.fromId(userId),
                                            text = intl.translate(id = "command.game.list.empty.message")
                                        )
                                    } else {
                                        currentGameMap[userId] = game

                                        bot.sendMessage(
                                            chatId = ChatId.fromId(userId),
                                            text = intl.translate(id = "command.edit.character.page.list.message"),
                                            replyMarkup = CharacterPageKeyboardReplyMarkup.create(characterPages)
                                        )
                                    }
                                }.onFailure {
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(userId),
                                        text = intl.translate(id = "command.error.message")
                                    )

                                    currentCharacterPageMap.remove(userId)
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

                var characterPage = currentCharacterPageMap[userId]

                runCatching {
                    if (characterPage != null) {
                        characterPage!!.content = CharacterPage.Content(
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
                                val characterPageTitle =  Normalizer.normalize(characterPage!!.title, Normalizer.Form.NFKD)
                                    .replace("\\p{M}".toRegex(), "")
                                    .lowercase()
                                    .replace(" ", "-")

                                telegraphApi.createPage(TelegraphApi.CreatePage(
                                    accessToken = telegraphAccessToken,
                                    title = "${game.code}-${characterPageTitle}",
                                    authorName = telegraphUsername,
                                    content = "[${characterPage!!.content}]"
                                )).onSuccess { page ->
                                    characterPage!!.path = page.path

                                    telegraphApi.editPage(characterPage!!.path, TelegraphApi.EditPage(
                                        accessToken = telegraphAccessToken,
                                        title = characterPage!!.title,
                                        content = "[${characterPage!!.content}]",
                                        authorName = telegraphUsername
                                    )).getOrThrow()
                                }.onSuccess {
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(userId),
                                        text = intl.translate(
                                            id = "command.new.character.page.success.message",
                                            value = "title" to characterPage!!.title
                                        )
                                    )
                                }
                            }
                            Command.EDIT_CHAR_PAGE -> {
                                telegraphApi.editPage(characterPage!!.path, TelegraphApi.EditPage(
                                    accessToken = telegraphAccessToken,
                                    title = characterPage!!.title,
                                    content = "[${characterPage!!.content}]",
                                    authorName = telegraphUsername
                                )).getOrThrow()

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.edit.character.page.success.message",
                                        value = "title" to characterPage!!.title
                                    )
                                )
                            }
                            else -> return@message
                        }

                        currentCharacterPageMap.remove(userId)
                        currentGameMap.remove(userId)
                        currentCommandMap.remove(userId)
                    } else {
                        when (command) {
                            Command.NEW_CHAR_PAGE -> {
                                val characterPageTitle = CharacterPage.Title(message.text!!).value

                                val pageList = telegraphApi.getPageList(TelegraphApi.GetPageList(
                                    accessToken = telegraphAccessToken
                                )).getOrThrow()

                                val pageExists = pageList.pages
                                    .filter { page -> page.path.contains(game.code) }
                                    .any { page -> page.title.equals(characterPageTitle, ignoreCase = true) }

                                if (pageExists) {
                                    throw CharacterPage.Title.ExistsException()
                                }

                                characterPage = CharacterPage().apply {
                                    title = characterPageTitle.lowercase().trim().split(" ").joinToString(" ") { part ->
                                        part.replaceFirstChar { it.uppercase() }
                                    }
                                }

                                currentCharacterPageMap[userId] = characterPage!!

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.new.character.page.content.message",
                                        value = "title" to characterPage!!.title
                                    )
                                )
                            }
                            Command.EDIT_CHAR_PAGE -> {
                                val characterPageTitle = message.text!!

                                val pageList = telegraphApi.getPageList(TelegraphApi.GetPageList(
                                    accessToken = telegraphAccessToken
                                )).getOrThrow()

                                val page = pageList.pages
                                    .filter { page -> page.path.contains(game.code) }
                                    .firstOrNull { page ->
                                        page.title.equals(characterPageTitle, ignoreCase = true)
                                    }?.let { page ->
                                        telegraphApi.getPage(page.path, TelegraphApi.GetPage()).getOrThrow()
                                    } ?: return@message

                                val characterPageContent = buildString {
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

                                characterPage = CharacterPage().apply {
                                    path = page.path
                                    title = page.title
                                    content = characterPageContent
                                }

                                currentCharacterPageMap[userId] = characterPage!!

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.edit.character.page.content.message1",
                                        value = "title" to characterPage!!.title
                                    ),
                                    replyMarkup = ReplyKeyboardRemove()
                                )

                                bot.sendMessage(chatId = ChatId.fromId(userId), text = characterPage!!.content)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.edit.character.page.content.message2")
                                )
                            }
                            else -> return@message
                        }
                    }
                }.onFailure { error ->
                    when(error) {
                        is CharacterPage.Title.BlankException -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.page.title.blank.message")
                        )
                        is CharacterPage.Title.LengthException -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.page.title.length.message")
                        )
                        is CharacterPage.Title.InvalidException -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.page.title.invalid.message")
                        )
                        is CharacterPage.Title.ExistsException -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.page.title.exists.message")
                        )
                        is CharacterPage.Content.BlankException -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.page.content.blank.message")
                        )
                        is CharacterPage.Content.LengthException -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.page.content.length.message")
                        )
                        else -> {
                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate("command.error.message"),
                                replyMarkup = ReplyKeyboardRemove()
                            )

                            currentCharacterPageMap.remove(userId)
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

                currentCharacterPageMap.remove(userId)
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