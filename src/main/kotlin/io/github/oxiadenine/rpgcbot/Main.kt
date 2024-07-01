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
import io.github.oxiadenine.rpgcbot.repository.*
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
import io.ktor.server.request.*
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

class UnauthorizedError : Error()

fun String.normalize() = Normalizer.normalize(
    this,
    Normalizer.Form.NFKD
).replace("\\p{M}".toRegex(), "")

fun Application.bot(
    telegraphApi: TelegraphApi,
    userRepository: UserRepository,
    gameRepository: GameRepository,
    characterPageRepository: CharacterPageRepository
) {
    val config =  environment.config.config("bot")

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
                            val users = userRepository.read()

                            if (users.isEmpty() || !users.any { user -> user.id == userId }) {
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

                            val games = gameRepository.read()

                            if (games.isEmpty()) {
                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.game.list.empty.message")
                                )
                            } else {
                                currentCommandMap[userId] = Command.valueOf(commandName.uppercase())

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.game.list.message"),
                                    replyMarkup = GameInlineKeyboardMarkup.create(games)
                                )
                            }
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

                        when (currentCommand) {
                            Command.NEWCHARPAGE, Command.NEWCHARRANKPAGE -> {
                                val characterPageIsRanking = currentCommand.name == Command.NEWCHARRANKPAGE.name

                                val characterPageExists = if (characterPageIsRanking) {
                                    currentGame.characterPages.filter { characterPage ->
                                        characterPage.isRanking
                                    }
                                } else {
                                    currentGame.characterPages.filter { characterPage ->
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
                                    currentGame.characterPages.filter { characterPage ->
                                        characterPage.isRanking
                                    }
                                } else {
                                    currentGame.characterPages.filter { characterPage ->
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
                                currentCharacterPage.gameKey = currentGame.key

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
                            currentCharacterPage.gameKey = currentGame.key

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
                    val game = gameRepository.read(callbackQuery.data)!!.let { game ->
                        game.characterPages = characterPageRepository.read(game)

                        game
                    }

                    when (currentCommand) {
                        Command.NEWCHARPAGE, Command.NEWCHARRANKPAGE -> {
                            currentGameMap[userId] = game
                            currentCharacterPageMap[userId] = CharacterPage()

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = "command.new.character.page.title.message")
                            )
                        }
                        Command.EDITCHARPAGE, Command.EDITCHARRANKPAGE -> {
                            val characterPages = if (currentCommand.name == Command.EDITCHARRANKPAGE.name) {
                                game.characterPages.filter { characterPage ->
                                    characterPage.isRanking
                                }
                            } else {
                                game.characterPages.filter { characterPage ->
                                    !characterPage.isRanking
                                }
                            }

                            if (characterPages.isEmpty()) {
                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(id = "command.game.list.character.page.list.empty.message")
                                )
                            } else {
                                currentGameMap[userId] = game
                                currentCharacterPageMap[userId] = CharacterPage()

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
                            description = gameRepository.read(characterPage.gameKey)!!.name
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
    userRepository: UserRepository,
    gameRepository: GameRepository,
    characterPageRepository: CharacterPageRepository
) {
    install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
        json()
    }
    install(Routing) {
        post("/users") {
            val body = call.receive<JsonObject>()

            val users = body["users"]!!.jsonArray.map { jsonElement ->
                val userId = jsonElement.jsonObject["id"]!!.jsonPrimitive.content.toLong()
                val userName = jsonElement.jsonObject["name"]!!.jsonPrimitive.content

                val user = User(userId, userName)

                if (userRepository.read(user.id) == null) {
                    userRepository.create(user)
                } else userRepository.update(user)

                user
            }

            val response = buildJsonObject {
                put("ok", true)
                put("result", buildJsonArray {
                    users.map { user ->
                        add(buildJsonObject {
                            put("id", user.id)
                            put("name", user.name)
                        })
                    }
                })
            }

            call.respond(response)
        }
        post("/games") {
            val body = call.receive<JsonObject>()

            val games = body["games"]!!.jsonArray.map { jsonElement ->
                val gameName = jsonElement.jsonPrimitive.content
                val gameKey = gameName.lowercase().split(" ")
                    .joinToString("") { part -> "${part[0]}" }

                val game = Game(gameKey, gameName)

                if (gameRepository.read(game.key) == null) {
                    gameRepository.create(game)
                } else gameRepository.update(game)

                game
            }

            val response = buildJsonObject {
                put("ok", true)
                put("result", buildJsonArray {
                    games.map { game ->
                        add(buildJsonObject {
                            put("key", game.key)
                            put("name", game.name)
                        })
                    }
                })
            }

            call.respond(response)
        }
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

                val characterPageImage =
                    if (page.content!!.last().jsonObject["tag"]!!.jsonPrimitive.content == "figure") {
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
                    gameKey = page.path.substringBefore("-")
                }

                if (characterPageRepository.read(characterPage.path) == null) {
                    characterPageRepository.create(characterPage)
                } else characterPageRepository.update(characterPage)

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

    val userRepository = UserRepository(database)
    val gameRepository = GameRepository(database)
    val characterPageRepository = CharacterPageRepository(database)

    val appEngineEnv = applicationEngineEnvironment {
        config = appConfig

        module {
            bot(telegraphApi, userRepository, gameRepository, characterPageRepository)
            api(telegraphApi, userRepository, gameRepository, characterPageRepository)
        }

        connector {
            host = config.property("server.host").getString()
            port = config.property("server.port").getString().toInt()
        }
    }

    embeddedServer(io.ktor.server.cio.CIO, appEngineEnv).start(true)
}