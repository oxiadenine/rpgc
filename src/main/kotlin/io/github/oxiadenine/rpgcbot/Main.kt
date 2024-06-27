package io.github.oxiadenine.rpgcbot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.inlineQuery
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ReplyKeyboardRemove
import com.github.kotlintelegrambot.entities.inlinequeryresults.InlineQueryResult
import com.github.kotlintelegrambot.entities.inlinequeryresults.InputMessageContent
import com.github.kotlintelegrambot.extensions.filters.Filter
import com.typesafe.config.ConfigFactory
import io.github.oxiadenine.rpgcbot.network.TelegraphApi
import io.github.oxiadenine.rpgcbot.repository.CharacterPage
import io.github.oxiadenine.rpgcbot.repository.CharacterPageRepository
import io.github.oxiadenine.rpgcbot.view.CharacterPageKeyboardReplyMarkup
import io.github.oxiadenine.rpgcbot.view.GameInlineKeyboardMarkup
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

enum class Command {
    START,
    NEWCHARPAGE,
    EDITCHARPAGE,
    NEWCHARRANKPAGE,
    EDITCHARRANKPAGE,
    CANCEL
}

data class Game(val key: String, val name: String)

class UnauthorizedError : Error()

fun String.normalize() = Normalizer.normalize(
    this,
    Normalizer.Form.NFKD
).replace("\\p{M}".toRegex(), "")

fun Application.bot(
    telegraphApi: TelegraphApi,
    characterPageRepository: CharacterPageRepository
) {
    val config =  environment.config.config("bot")

    val userIds = config.property("userWhitelist").getString()
        .split(",")
        .map { userId -> userId.toLong() }

    val games = config.property("gameList").getString()
        .split(",")
        .map { gameName -> Game(
            key = gameName.lowercase().split(" ").joinToString("") { "${it[0]}" },
            name = gameName
        )}

    val bot = bot {
        token = config.property("token").getString()

        val currentCommandMap = ConcurrentHashMap<Long, Command>()
        val currentGameMap = ConcurrentHashMap<Long, Game>()
        val currentCharacterPageMap = ConcurrentHashMap<Long, CharacterPage>()

        dispatch {
            message(Filter.Command) {
                val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = message.chat.id

                val commandName = message.text!!.substringAfter("/").uppercase()

                runCatching {
                    when (commandName) {
                        Command.START.name -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.start.message")
                        )
                        Command.NEWCHARPAGE.name,
                        Command.EDITCHARPAGE.name,
                        Command.NEWCHARRANKPAGE.name,
                        Command.EDITCHARRANKPAGE.name -> {
                            if (!userIds.contains(userId)) {
                                throw UnauthorizedError()
                            }

                            if (currentCommandMap[userId] != null) {
                                currentCharacterPageMap.remove(userId)
                                currentGameMap.remove(userId)
                                currentCommandMap.remove(userId)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = "\u2062",
                                    replyMarkup = ReplyKeyboardRemove()
                                ).getOrNull()?.let { message ->
                                    bot.deleteMessage(
                                        chatId = ChatId.fromId(userId),
                                        messageId = message.messageId
                                    )
                                }
                            }

                            currentCommandMap[userId] = Command.valueOf(commandName.uppercase())

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = "command.game.list.message"),
                                replyMarkup = GameInlineKeyboardMarkup.create(games)
                            )
                        }
                        Command.CANCEL.name -> {
                            val currentCommand = currentCommandMap[userId] ?: return@message

                            currentCharacterPageMap.remove(userId)
                            currentGameMap.remove(userId)
                            currentCommandMap.remove(userId)

                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = intl.translate(
                                    id = "command.cancel.message",
                                    value = "command" to currentCommand.name.lowercase()
                                ),
                                replyMarkup = ReplyKeyboardRemove()
                            )
                        }
                        else -> {
                            if (currentCommandMap[userId] != null) return@message

                            if (commandName.length < 3) return@message

                            characterPageRepository.read().filter { characterPage ->
                                characterPage.title.value
                                    .normalize()
                                    .replace("[^a-zA-Z0-9]".toRegex(), "")
                                    .contains(commandName, true)
                            }.map { characterPage ->
                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = characterPage.url
                                )
                            }
                        }
                    }
                }.onFailure { error ->
                    when (error) {
                        is UnauthorizedError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.unauthorized.message")
                        )
                        else -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate(id = "command.error.message")
                        )
                    }
                }
            }

            message(Filter.Text) {
                val intl = message.from?.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = message.chat.id

                val currentCommand = currentCommandMap[userId] ?: return@message
                val currentGame = currentGameMap[userId] ?: return@message

                val currentCharacterPage = currentCharacterPageMap[userId] ?: return@message

                if (currentCharacterPage.content.value.isNotEmpty()) return@message

                runCatching {
                    if (currentCharacterPage.title.value.isEmpty()) {
                        val characterPageTitle = CharacterPage.Title(message.text!!)

                        val characterPages = characterPageRepository.read()

                        when (currentCommand) {
                            Command.NEWCHARPAGE, Command.NEWCHARRANKPAGE -> {
                                val characterPageIsRanking = currentCommand.name == Command.NEWCHARRANKPAGE.name

                                val characterPageExists = if (characterPageIsRanking) {
                                    characterPages.filter { characterPage ->
                                        characterPage.path.contains(currentGame.key) &&
                                                characterPage.isRanking
                                    }
                                } else {
                                    characterPages.filter { characterPage ->
                                        characterPage.path.contains(currentGame.key) &&
                                                !characterPage.isRanking
                                    }
                                }.any { characterPage ->
                                    characterPage.title.value.normalize().equals(
                                        characterPageTitle.value.normalize(),
                                        true
                                    )
                                }

                                if (characterPageExists) {
                                    throw CharacterPage.Title.ExistsError()
                                }

                                currentCharacterPage.title = characterPageTitle
                                currentCharacterPage.isRanking = characterPageIsRanking

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.new.character.page.content.message",
                                        value = "title" to currentCharacterPage.title.value
                                    )
                                )
                            }
                            Command.EDITCHARPAGE, Command.EDITCHARRANKPAGE -> {
                                val characterPage = if (currentCommand.name == Command.EDITCHARRANKPAGE.name) {
                                    characterPages.filter { characterPage ->
                                        characterPage.path.contains(currentGame.key) &&
                                                characterPage.isRanking
                                    }
                                } else {
                                    characterPages.filter { characterPage ->
                                        characterPage.path.contains(currentGame.key) &&
                                                !characterPage.isRanking
                                    }
                                }.firstOrNull { characterPage ->
                                    characterPage.title.value.normalize().equals(
                                        characterPageTitle.value.normalize(),
                                        true
                                    )
                                } ?: return@message

                                currentCharacterPage.path = characterPage.path
                                currentCharacterPage.title = characterPage.title
                                currentCharacterPage.url = characterPage.url
                                currentCharacterPage.isRanking = characterPage.isRanking

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.edit.character.page.content.message1",
                                        value = "title" to currentCharacterPage.title.value
                                    ),
                                    replyMarkup = ReplyKeyboardRemove()
                                )

                                val characterPageContentHtml = buildString {
                                    Json.decodeFromString<JsonArray>(characterPage.content.value).map { node ->
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

                                bot.sendMessage(chatId = ChatId.fromId(userId), text = characterPageContentHtml)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.edit.character.page.content.message2")
                                )
                            }
                            else -> return@message
                        }
                    } else {
                        val characterPageContentJson = buildString {
                            append("[")
                            append(Jsoup.parse(message.text!!).body().select(">*")
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
                                        Json.encodeToString(TelegraphApi.NodeElement(
                                            tag = element.tagName(),
                                            children = listOf(element.text())
                                        ))
                                    }
                                })
                            append("]")
                        }

                        currentCharacterPage.content = CharacterPage.Content(characterPageContentJson)

                        when (currentCommand) {
                            Command.NEWCHARPAGE -> {
                                val page = telegraphApi.createPage(TelegraphApi.CreatePage(
                                    title = buildString {
                                        append("${currentGame.key}-")
                                        append(currentCharacterPage.title.value
                                            .normalize()
                                            .lowercase()
                                            .replace(" ", "-")
                                        )
                                    },
                                    content = currentCharacterPage.content.value
                                )).getOrThrow()

                                currentCharacterPage.path = page.path
                                currentCharacterPage.url = page.url

                                telegraphApi.editPage(currentCharacterPage.path, TelegraphApi.EditPage(
                                    title = currentCharacterPage.title.value,
                                    content = currentCharacterPage.content.value
                                )).getOrThrow()

                                characterPageRepository.create(currentCharacterPage)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.new.character.page.success.message",
                                        value = "title" to currentCharacterPage.title.value
                                    )
                                )

                                currentCharacterPageMap.remove(userId)
                                currentGameMap.remove(userId)
                                currentCommandMap.remove(userId)
                            }
                            Command.NEWCHARRANKPAGE -> bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = "command.new.character.page.content.image.message")
                            )
                            Command.EDITCHARPAGE -> {
                                telegraphApi.editPage(currentCharacterPage.path, TelegraphApi.EditPage(
                                    title = currentCharacterPage.title.value,
                                    content = currentCharacterPage.content.value
                                )).getOrThrow()

                                characterPageRepository.update(currentCharacterPage)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.edit.character.page.success.message",
                                        value = "title" to currentCharacterPage.title.value
                                    )
                                )

                                currentCharacterPageMap.remove(userId)
                                currentGameMap.remove(userId)
                                currentCommandMap.remove(userId)
                            }
                            Command.EDITCHARRANKPAGE -> bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = "command.edit.character.page.content.image.message")
                            )
                            else -> return@message
                        }
                    }
                }.onFailure { error ->
                    when (error) {
                        is CharacterPage.Title.BlankError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.page.title.blank.message")
                        )
                        is CharacterPage.Title.LengthError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.page.title.length.message")
                        )
                        is CharacterPage.Title.InvalidError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.page.title.invalid.message")
                        )
                        is CharacterPage.Title.ExistsError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.page.title.exists.message")
                        )
                        is CharacterPage.Content.BlankError -> bot.sendMessage(
                            chatId = ChatId.fromId(userId),
                            text = intl.translate("command.new.character.page.content.blank.message")
                        )
                        is CharacterPage.Content.LengthError -> bot.sendMessage(
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

                val currentCommand = currentCommandMap[userId] ?: return@message
                val currentGame = currentGameMap[userId] ?: return@message

                val currentCharacterPage = currentCharacterPageMap[userId] ?: return@message

                if (currentCharacterPage.content.value.isEmpty()) return@message

                runCatching {
                    val imageUrl = bot.getFile(message.photo!!.last().fileId).first?.let { response ->
                        if (response.isSuccessful) {
                            bot.downloadFile(response.body()?.result!!.filePath!!)
                        } else error(response.message())
                    }?.first?.let { response ->
                        if (response.isSuccessful) {
                            currentCharacterPage.image = response.body()!!.bytes()

                            telegraphApi.uploadImage(currentCharacterPage.image!!)
                        } else error(response.message())
                    } ?: return@message

                    val characterPageContentImageJson = Json.encodeToString(buildJsonArray {
                        addAll(Json.decodeFromString<JsonArray>(currentCharacterPage.content.value))
                        add(Json.encodeToJsonElement(TelegraphApi.Node(
                            tag = "figure",
                            children = listOf(TelegraphApi.NodeElement(
                                tag = "img",
                                attrs = TelegraphApi.Attributes(
                                    src = "https://telegra.ph$imageUrl"
                                )
                            ))
                        )))
                    })

                    currentCharacterPage.content = CharacterPage.Content(characterPageContentImageJson)

                    when (currentCommand) {
                        Command.NEWCHARRANKPAGE -> {
                            val page = telegraphApi.createPage(TelegraphApi.CreatePage(
                                title = buildString {
                                    append("${currentGame.key}-")
                                    append(currentCharacterPage.title.value
                                        .normalize()
                                        .lowercase()
                                        .replace(" ", "-")
                                    )
                                    append("=${CharacterPage.Paths.RANKING.name.lowercase()}")
                                },
                                content = currentCharacterPage.content.value
                            )).getOrThrow()

                            currentCharacterPage.path = page.path
                            currentCharacterPage.url = page.url

                            telegraphApi.editPage(currentCharacterPage.path, TelegraphApi.EditPage(
                                title = currentCharacterPage.title.value,
                                content = currentCharacterPage.content.value
                            )).getOrThrow()

                            characterPageRepository.create(currentCharacterPage)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(
                                    id = "command.new.character.page.success.message",
                                    value = "title" to currentCharacterPage.title.value
                                )
                            )
                        }
                        Command.EDITCHARRANKPAGE -> {
                            telegraphApi.editPage(currentCharacterPage.path, TelegraphApi.EditPage(
                                title = currentCharacterPage.title.value,
                                content = currentCharacterPage.content.value
                            )).getOrThrow()

                            characterPageRepository.update(currentCharacterPage)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(
                                    id = "command.edit.character.page.success.message",
                                    value = "title" to currentCharacterPage.title.value
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

            callbackQuery {
                val intl = callbackQuery.from.languageCode?.let { locale -> Intl(locale) } ?: Intl()

                val userId = callbackQuery.message?.chat?.id ?: return@callbackQuery

                val currentCommand = currentCommandMap[userId] ?: return@callbackQuery

                runCatching {
                    val game = games.first { game -> game.key == callbackQuery.data }

                    currentCharacterPageMap[userId] = CharacterPage()

                    when (currentCommand) {
                        Command.NEWCHARPAGE, Command.NEWCHARRANKPAGE -> {
                            currentGameMap[userId] = game

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = "command.new.character.page.title.message")
                            )
                        }
                        Command.EDITCHARPAGE, Command.EDITCHARRANKPAGE -> {
                            val characterPages = if (currentCommand.name == Command.EDITCHARRANKPAGE.name) {
                                characterPageRepository.read().filter { characterPage ->
                                    characterPage.path.contains(game.key) &&
                                            characterPage.isRanking
                                }
                            } else {
                                characterPageRepository.read().filter { characterPage ->
                                    characterPage.path.contains(game.key) &&
                                            !characterPage.isRanking
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

            inlineQuery {
                val pageTitleQuery = inlineQuery.query

                if (pageTitleQuery.isBlank() or pageTitleQuery.isEmpty()) return@inlineQuery

                val pageInlineQueryResults = characterPageRepository.read()
                    .filter { characterPage ->
                        characterPage.title.value.normalize().contains(
                            pageTitleQuery.normalize(),
                            true
                        )
                    }
                    .map { characterPage ->
                        InlineQueryResult.Article(
                            id = characterPage.path,
                            title = characterPage.title.value,
                            inputMessageContent = InputMessageContent.Text(characterPage.url),
                            description = games.first { game ->
                                game.key == characterPage.path.substringBefore("-")
                            }.name
                        )
                    }

                bot.answerInlineQuery(inlineQuery.id, pageInlineQueryResults)
            }
        }
    }

    bot.startPolling()
}

fun Application.api(
    telegraphApi: TelegraphApi,
    characterPageRepository: CharacterPageRepository
) {
    install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
        json()
    }
    install(Routing) {
        post("/characterPages") {
            val pageCount = telegraphApi.getAccountInfo(TelegraphApi.GetAccountInfo(
                fields = listOf("page_count")
            )).getOrThrow().pageCount!!

            val pages = mutableListOf<TelegraphApi.Page>()

            var offset = 0
            val limit = 50

            while (offset < pageCount) {
                val pageList = telegraphApi.getPageList(TelegraphApi.GetPageList(
                    offset = offset,
                    limit = limit
                )).getOrThrow()

                pages.addAll(pageList.pages)

                offset += limit
            }

            val characterPages = pages.map { partialPage ->
                val page = telegraphApi.getPage(partialPage.path, TelegraphApi.GetPage()).getOrThrow()

                val characterPageImage = if (page.content!!.last().jsonObject["tag"]!!.jsonPrimitive.content == "figure") {
                    val imageSrc = page.content.last().jsonObject["children"]!!.jsonArray
                        .last().jsonObject["attrs"]!!.jsonObject["src"]!!.jsonPrimitive.content

                    telegraphApi.downloadImage(imageSrc)
                } else null

                val characterPage = CharacterPage().apply {
                    path = page.path
                    title = CharacterPage.Title(page.title)
                    content = CharacterPage.Content(Json.encodeToString(page.content))
                    url = page.url
                    isRanking = page.path.contains(CharacterPage.Paths.RANKING.name, true)
                    image = characterPageImage
                }

                characterPageRepository.create(characterPage)

                characterPage
            }

            val response = buildJsonObject {
                put("ok", true)
                put("result", buildJsonArray {
                    characterPages.map { characterPage ->
                        add(buildJsonObject {
                            put("path", characterPage.path)
                            put("title", characterPage.title.value)
                            put("url", characterPage.url)
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

    val httpClient = HttpClient(io.ktor.client.engine.cio.CIO) {
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
    }
    val database = Database.create(appConfig.config("database"))

    val telegraphApi = TelegraphApi(appConfig.config("telegraph"), httpClient)
    val characterPageRepository = CharacterPageRepository(database)

    val appEngineEnv = applicationEngineEnvironment {
        config = appConfig

        module {
            bot(telegraphApi, characterPageRepository)
            api(telegraphApi, characterPageRepository)
        }

        connector {
            host = config.property("server.host").getString()
            port = config.property("server.port").getString().toInt()
        }
    }

    embeddedServer(io.ktor.server.cio.CIO, appEngineEnv).start(true)
}