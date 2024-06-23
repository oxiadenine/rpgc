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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

enum class Command {
    START,
    NEW_CHAR_PAGE,
    EDIT_CHAR_PAGE,
    NEW_CHAR_RANK_PAGE,
    EDIT_CHAR_RANK_PAGE,
    CANCEL;

    override fun toString() = name.replace("_", "").lowercase()
}

data class Game(val code: String, val name: String)

fun main() {
    val telegramBotToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: ""
    val userIdWhitelist = System.getenv("USER_ID_WHITELIST") ?: ""
    val gameList = System.getenv("GAME_NAME_LIST") ?: ""
    val telegraphUsername = System.getenv("TELEGRAPH_USERNAME") ?: ""
    val telegraphAccessToken = System.getenv("TELEGRAPH_ACCESS_TOKEN") ?: ""

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
            contentType(ContentType.Application.Json)
        }

        expectSuccess = true
    })

    val rpgcBot = bot {
        token = telegramBotToken

        val currentCommandMap = ConcurrentHashMap<Long, Command>()
        val currentGameMap = ConcurrentHashMap<Long, Game>()
        val currentCharacterPageMap = ConcurrentHashMap<Long, CharacterPage>()

        dispatch {
            command(Command.START.toString()) {
                val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = message.chat.id

                bot.sendMessage(
                    chatId = ChatId.fromId(userId),
                    text = intl.translate(id = "command.start.message")
                )
            }

            Command.entries.drop(1).dropLast(1).map { command ->
                command(command.toString()) {
                    val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

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

            command(Command.CANCEL.toString()) {
                val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

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

            games.map { game ->
                callbackQuery(game.code) {
                    val intl = callbackQuery.from.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                    val userId = callbackQuery.message?.chat?.id ?: return@callbackQuery

                    val command = currentCommandMap[userId] ?: return@callbackQuery

                    currentCharacterPageMap[userId] = CharacterPage()

                    runCatching {
                        when (command) {
                            Command.NEW_CHAR_PAGE, Command.NEW_CHAR_RANK_PAGE -> {
                                currentGameMap[userId] = game

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.new.character.page.title.message")
                                )
                            }
                            Command.EDIT_CHAR_PAGE, Command.EDIT_CHAR_RANK_PAGE -> {
                                val pageCount = telegraphApi.getAccountInfo(TelegraphApi.GetAccountInfo(
                                    accessToken = telegraphAccessToken,
                                    fields = listOf("page_count")
                                )).getOrThrow().pageCount!!

                                val pages = mutableListOf<TelegraphApi.Page>()

                                var offset = 0
                                val limit = 50

                                while (offset < pageCount) {
                                    val pageList = telegraphApi.getPageList(TelegraphApi.GetPageList(
                                        accessToken = telegraphAccessToken,
                                        offset = offset,
                                        limit = limit
                                    )).getOrThrow()

                                    pages.addAll(pageList.pages)

                                    offset += limit
                                }

                                val characterPages = if (command.name == Command.EDIT_CHAR_RANK_PAGE.name) {
                                    pages.filter { page ->
                                        page.path.contains(game.code) &&
                                                page.path.contains(CharacterPage.Paths.RANKING.name, ignoreCase = true)
                                    }
                                } else {
                                    pages.filter { page -> page.path.contains(game.code) &&
                                            !page.path.contains(CharacterPage.Paths.RANKING.name, ignoreCase = true)
                                    }
                                }.map { page ->
                                    CharacterPage().apply {
                                        path = page.path
                                        title = page.title
                                        isRanking = command.name == Command.EDIT_CHAR_RANK_PAGE.name
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
                            }
                            else -> return@callbackQuery
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
            }

            message(Filter.Text) {
                val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = message.chat.id

                val command = currentCommandMap[userId] ?: return@message
                val game = currentGameMap[userId] ?: return@message

                val characterPage = currentCharacterPageMap[userId] ?: return@message

                if (characterPage.content.isNotEmpty()) return@message

                runCatching {
                    if (characterPage.title.isEmpty()) {
                        val pageCount = telegraphApi.getAccountInfo(TelegraphApi.GetAccountInfo(
                            accessToken = telegraphAccessToken,
                            fields = listOf("page_count")
                        )).getOrThrow().pageCount!!

                        val pages = mutableListOf<TelegraphApi.Page>()

                        var offset = 0
                        val limit = 50

                        while (offset < pageCount) {
                            val pageList = telegraphApi.getPageList(TelegraphApi.GetPageList(
                                accessToken = telegraphAccessToken,
                                offset = offset,
                                limit = limit
                            )).getOrThrow()

                            pages.addAll(pageList.pages)

                            offset += limit
                        }

                        when (command) {
                            Command.NEW_CHAR_PAGE, Command.NEW_CHAR_RANK_PAGE -> {
                                val characterPageTitle = CharacterPage.Title(message.text!!).value

                                val pageExists = if (command.name == Command.NEW_CHAR_RANK_PAGE.name) {
                                    pages.filter { page -> page.path.contains(game.code) &&
                                            page.path.contains(CharacterPage.Paths.RANKING.name, ignoreCase = true)
                                    }
                                } else {
                                    pages.filter { page -> page.path.contains(game.code) &&
                                            !page.path.contains(CharacterPage.Paths.RANKING.name, ignoreCase = true)
                                    }
                                }.any { page -> page.title.equals(characterPageTitle, ignoreCase = true) }

                                if (pageExists) {
                                    throw CharacterPage.Title.ExistsException()
                                }

                                characterPage.title = characterPageTitle.lowercase().trim().replaceFirstChar { it.uppercase() }
                                characterPage.isRanking = command.name == Command.NEW_CHAR_RANK_PAGE.name

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.new.character.page.content.message",
                                        value = "title" to characterPage.title
                                    )
                                )
                            }
                            Command.EDIT_CHAR_PAGE, Command.EDIT_CHAR_RANK_PAGE -> {
                                val characterPageTitle = message.text!!

                                val page = if (command.name == Command.EDIT_CHAR_RANK_PAGE.name) {
                                    pages.filter { page -> page.path.contains(game.code) &&
                                            page.path.contains(CharacterPage.Paths.RANKING.name, ignoreCase = true)
                                    }
                                } else {
                                    pages.filter { page -> page.path.contains(game.code) &&
                                            !page.path.contains(CharacterPage.Paths.RANKING.name, ignoreCase = true)
                                    }
                                }.firstOrNull { page -> page.title.equals(characterPageTitle, ignoreCase = true) }?.let { page ->
                                    telegraphApi.getPage(page.path, TelegraphApi.GetPage()).getOrThrow()
                                } ?: return@message

                                val characterPageContent = buildString {
                                    page.content!!.map { node ->
                                        if (node.jsonObject["tag"]!!.jsonPrimitive.content != "figure") {
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
                                        } else append("")
                                    }
                                }

                                characterPage.path = page.path
                                characterPage.title = page.title
                                characterPage.isRanking = command.name == Command.EDIT_CHAR_RANK_PAGE.name

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.edit.character.page.content.message1",
                                        value = "title" to characterPage.title
                                    ),
                                    replyMarkup = ReplyKeyboardRemove()
                                )

                                bot.sendMessage(chatId = ChatId.fromId(userId), text = characterPageContent)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.edit.character.page.content.message2")
                                )
                            }
                            else -> return@message
                        }
                    } else {
                        characterPage.content = CharacterPage.Content(
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
                                val characterPageTitle = buildString {
                                    append(game.code)
                                    append("-")
                                    append(Normalizer.normalize(characterPage.title, Normalizer.Form.NFKD)
                                        .replace("\\p{M}".toRegex(), "")
                                        .lowercase()
                                        .replace(" ", "-"))
                                }

                                val page = telegraphApi.createPage(TelegraphApi.CreatePage(
                                    accessToken = telegraphAccessToken,
                                    title = characterPageTitle,
                                    authorName = telegraphUsername,
                                    content = "[${characterPage.content}]"
                                )).getOrThrow()

                                characterPage.path = page.path

                                telegraphApi.editPage(characterPage.path, TelegraphApi.EditPage(
                                    accessToken = telegraphAccessToken,
                                    title = characterPage.title,
                                    content = "[${characterPage.content}]",
                                    authorName = telegraphUsername
                                )).getOrThrow()

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.new.character.page.success.message",
                                        value = "title" to characterPage.title
                                    )
                                )

                                currentCharacterPageMap.remove(userId)
                                currentGameMap.remove(userId)
                                currentCommandMap.remove(userId)
                            }
                            Command.NEW_CHAR_RANK_PAGE -> {
                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.new.character.page.content.image.message")
                                )
                            }
                            Command.EDIT_CHAR_PAGE -> {
                                telegraphApi.editPage(characterPage.path, TelegraphApi.EditPage(
                                    accessToken = telegraphAccessToken,
                                    title = characterPage.title,
                                    content = "[${characterPage.content}]",
                                    authorName = telegraphUsername
                                )).getOrThrow()

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.edit.character.page.success.message",
                                        value = "title" to characterPage.title
                                    )
                                )

                                currentCharacterPageMap.remove(userId)
                                currentGameMap.remove(userId)
                                currentCommandMap.remove(userId)
                            }
                            Command.EDIT_CHAR_RANK_PAGE -> {
                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.edit.character.page.content.image.message")
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

            message(Filter.Photo) {
                val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = message.chat.id

                val command = currentCommandMap[userId] ?: return@message
                val game = currentGameMap[userId] ?: return@message

                val characterPage = currentCharacterPageMap[userId] ?: return@message

                if (characterPage.content.isEmpty()) return@message

                val imageUrl = bot.getFile(message.photo!!.last().fileId).first?.let { response ->
                    if (response.isSuccessful) {
                        bot.downloadFile(response.body()?.result!!.filePath!!)
                    } else error(response.message())
                }?.first?.let { response ->
                    if (response.isSuccessful) {
                        telegraphApi.uploadImage(response.body()!!.bytes())
                    } else error(response.message())
                } ?: return@message

                characterPage.content = buildString {
                    append("${characterPage.content},")
                    append(Json.encodeToString(TelegraphApi.Node(
                        tag = "figure",
                        children = listOf(TelegraphApi.NodeElement(
                            tag = "img",
                            attrs = TelegraphApi.Attributes(
                                src = "https://telegra.ph$imageUrl"
                            )
                        ))
                    )))
                }

                runCatching {
                    when (command) {
                        Command.NEW_CHAR_RANK_PAGE -> {
                            val characterPageTitle = buildString {
                                append(game.code)
                                append("-")
                                append(Normalizer.normalize(characterPage.title, Normalizer.Form.NFKD)
                                    .replace("\\p{M}".toRegex(), "")
                                    .lowercase()
                                    .replace(" ", "-"))
                                append("-")
                                append(CharacterPage.Paths.RANKING.name.lowercase())
                            }

                            val page = telegraphApi.createPage(TelegraphApi.CreatePage(
                                accessToken = telegraphAccessToken,
                                title = characterPageTitle,
                                authorName = telegraphUsername,
                                content = "[${characterPage.content}]"
                            )).getOrThrow()

                            characterPage.path = page.path

                            telegraphApi.editPage(characterPage.path, TelegraphApi.EditPage(
                                accessToken = telegraphAccessToken,
                                title = characterPage.title,
                                content = "[${characterPage.content}]",
                                authorName = telegraphUsername
                            )).getOrThrow()

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(
                                    id = "command.new.character.page.success.message",
                                    value = "title" to characterPage.title
                                )
                            )
                        }
                        Command.EDIT_CHAR_RANK_PAGE -> {
                            telegraphApi.editPage(characterPage.path, TelegraphApi.EditPage(
                                accessToken = telegraphAccessToken,
                                title = characterPage.title,
                                content = "[${characterPage.content}]",
                                authorName = telegraphUsername
                            )).getOrThrow()

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(
                                    id = "command.edit.character.page.success.message",
                                    value = "title" to characterPage.title
                                )
                            )
                        }
                        else -> return@message
                    }

                    currentCharacterPageMap.remove(userId)
                    currentGameMap.remove(userId)
                    currentCommandMap.remove(userId)
                }.onFailure {
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

            inlineQuery {
                val intl = inlineQuery.from.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = inlineQuery.from.id

                val pageTitleQuery = inlineQuery.query

                if (pageTitleQuery.isBlank() or pageTitleQuery.isEmpty()) return@inlineQuery

                telegraphApi.getAccountInfo(TelegraphApi.GetAccountInfo(
                    accessToken = telegraphAccessToken,
                    fields = listOf("page_count")
                )).onSuccess { account ->
                    val pages = mutableListOf<TelegraphApi.Page>()

                    var offset = 0
                    val limit = 50

                    while (offset < account.pageCount!!) {
                        val pageList = telegraphApi.getPageList(TelegraphApi.GetPageList(
                            accessToken = telegraphAccessToken,
                            offset = offset,
                            limit = limit
                        )).getOrThrow()

                        pages.addAll(pageList.pages)

                        offset += limit
                    }

                    val pageInlineQueryResults = pages
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