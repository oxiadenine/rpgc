package io.github.oxiadenine.rpgcbot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.*
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ReplyKeyboardRemove
import com.github.kotlintelegrambot.entities.inlinequeryresults.InlineQueryResult
import com.github.kotlintelegrambot.entities.inlinequeryresults.InputMessageContent
import com.github.kotlintelegrambot.extensions.filters.Filter
import com.typesafe.config.ConfigFactory
import io.github.oxiadenine.rpgcbot.network.TelegraphApi
import io.github.oxiadenine.rpgcbot.repository.CharacterPageEntity
import io.github.oxiadenine.rpgcbot.repository.CharacterPageRepository
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
    val config = ConfigFactory.load()

    val botConfig = config.getConfig("bot")
    val telegraphConfig = config.getConfig("telegraph")
    val databaseConfig = config.getConfig("database")

    val userIds = botConfig.getString("userWhitelist").split(",").map { userId -> userId.toLong() }
    val games = botConfig.getString("gameList").split(",").map { gameName -> Game(
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

    val characterPageRepository = CharacterPageRepository(Database.create(databaseConfig))

    val rpgcBot = bot {
        token = botConfig.getString("token")

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
                                val characterPageEntities = characterPageRepository.read()

                                val characterPages = if (command.name == Command.EDIT_CHAR_RANK_PAGE.name) {
                                    characterPageEntities.filter { characterPageEntity ->
                                        characterPageEntity.path.contains(game.code) && characterPageEntity.isRanking
                                    }
                                } else {
                                    characterPageEntities.filter { characterPageEntity ->
                                        characterPageEntity.path.contains(game.code) && !characterPageEntity.isRanking
                                    }
                                }.map { characterPageEntity ->
                                    CharacterPage().apply {
                                        path = characterPageEntity.path
                                        title = characterPageEntity.title
                                        content = characterPageEntity.content
                                        url = characterPageEntity.url
                                        isRanking = characterPageEntity.isRanking
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
                        val characterPageEntities = characterPageRepository.read()

                        when (command) {
                            Command.NEW_CHAR_PAGE, Command.NEW_CHAR_RANK_PAGE -> {
                                val characterPageTitle = CharacterPage.Title(message.text!!).value

                                val characterPageEntityExists = if (command.name == Command.NEW_CHAR_RANK_PAGE.name) {
                                    characterPageEntities.filter { characterPageEntity ->
                                        characterPageEntity.path.contains(game.code) && characterPageEntity.isRanking
                                    }
                                } else {
                                    characterPageEntities.filter { characterPageEntity ->
                                        characterPageEntity.path.contains(game.code) && !characterPageEntity.isRanking
                                    }
                                }.any { characterPageEntity -> characterPageEntity.title.equals(characterPageTitle, ignoreCase = true) }

                                if (characterPageEntityExists) {
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

                                val characterPageEntity = if (command.name == Command.EDIT_CHAR_RANK_PAGE.name) {
                                    characterPageEntities.filter { characterPageEntity ->
                                        characterPageEntity.path.contains(game.code) && characterPageEntity.isRanking
                                    }
                                } else {
                                    characterPageEntities.filter { characterPageEntity ->
                                        characterPageEntity.path.contains(game.code) && !characterPageEntity.isRanking
                                    }
                                }.firstOrNull { characterPageEntity ->
                                    characterPageEntity.title.equals(characterPageTitle, ignoreCase = true)
                                } ?: return@message

                                characterPage.path = characterPageEntity.path
                                characterPage.title = characterPageEntity.title
                                characterPage.url = characterPageEntity.url
                                characterPage.isRanking = characterPageEntity.isRanking

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.edit.character.page.content.message1",
                                        value = "title" to characterPage.title
                                    ),
                                    replyMarkup = ReplyKeyboardRemove()
                                )

                                val characterPageContentHtml = buildString {
                                    Json.decodeFromString<JsonArray>(characterPageEntity.content).map { node ->
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

                        characterPage.content = CharacterPage.Content(characterPageContentJson).value

                        when (command) {
                            Command.NEW_CHAR_PAGE -> {
                                val page = telegraphApi.createPage(TelegraphApi.CreatePage(
                                    accessToken = telegraphConfig.getString("accessToken"),
                                    title = buildString {
                                        append(game.code)
                                        append("-")
                                        append(Normalizer.normalize(characterPage.title, Normalizer.Form.NFKD)
                                            .replace("\\p{M}".toRegex(), "")
                                            .lowercase()
                                            .replace(" ", "-"))
                                    },
                                    authorName = telegraphConfig.getString("username"),
                                    content = characterPage.content
                                )).getOrThrow()

                                characterPage.path = page.path
                                characterPage.url = page.url

                                telegraphApi.editPage(characterPage.path, TelegraphApi.EditPage(
                                    accessToken = telegraphConfig.getString("accessToken"),
                                    title = characterPage.title,
                                    content = characterPage.content,
                                    authorName = telegraphConfig.getString("username")
                                )).getOrThrow()

                                val characterPageEntity = CharacterPageEntity(
                                    path = characterPage.path,
                                    title = characterPage.title,
                                    content = characterPage.content,
                                    url = characterPage.url,
                                    isRanking = characterPage.isRanking
                                )

                                characterPageRepository.create(characterPageEntity)

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
                                    accessToken = telegraphConfig.getString("accessToken"),
                                    title = characterPage.title,
                                    content = characterPage.content,
                                    authorName = telegraphConfig.getString("username")
                                )).getOrThrow()

                                val characterPageEntity = CharacterPageEntity(
                                    path = characterPage.path,
                                    title = characterPage.title,
                                    content = characterPage.content,
                                    url = characterPage.url,
                                    isRanking = characterPage.isRanking
                                )

                                characterPageRepository.update(characterPageEntity)

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
                        characterPage.image = response.body()!!.bytes()

                        telegraphApi.uploadImage(characterPage.image!!)
                    } else error(response.message())
                } ?: return@message

                characterPage.content = Json.encodeToString(buildJsonArray {
                    addAll(Json.decodeFromString<JsonArray>(characterPage.content))
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

                runCatching {
                    when (command) {
                        Command.NEW_CHAR_RANK_PAGE -> {
                            val page = telegraphApi.createPage(TelegraphApi.CreatePage(
                                accessToken = telegraphConfig.getString("accessToken"),
                                title = buildString {
                                    append(game.code)
                                    append("-")
                                    append(Normalizer.normalize(characterPage.title, Normalizer.Form.NFKD)
                                        .replace("\\p{M}".toRegex(), "")
                                        .lowercase()
                                        .replace(" ", "-"))
                                    append("-")
                                    append(CharacterPage.Paths.RANKING.name.lowercase())
                                },
                                authorName = telegraphConfig.getString("username"),
                                content = characterPage.content
                            )).getOrThrow()

                            characterPage.path = page.path
                            characterPage.url = page.url

                            telegraphApi.editPage(characterPage.path, TelegraphApi.EditPage(
                                accessToken = telegraphConfig.getString("accessToken"),
                                title = characterPage.title,
                                content = characterPage.content,
                                authorName = telegraphConfig.getString("username")
                            )).getOrThrow()

                            val characterPageEntity = CharacterPageEntity(
                                path = characterPage.path,
                                title = characterPage.title,
                                content = characterPage.content,
                                url = characterPage.url,
                                isRanking = characterPage.isRanking,
                                image = characterPage.image
                            )

                            characterPageRepository.create(characterPageEntity)

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
                                accessToken = telegraphConfig.getString("accessToken"),
                                title = characterPage.title,
                                content = characterPage.content,
                                authorName = telegraphConfig.getString("username")
                            )).getOrThrow()

                            val characterPageEntity = CharacterPageEntity(
                                path = characterPage.path,
                                title = characterPage.title,
                                content = characterPage.content,
                                url = characterPage.url,
                                isRanking = characterPage.isRanking,
                                image = characterPage.image
                            )

                            characterPageRepository.update(characterPageEntity)

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
                var pageTitleQuery = inlineQuery.query

                if (pageTitleQuery.isBlank() or pageTitleQuery.isEmpty()) return@inlineQuery

                pageTitleQuery = Normalizer.normalize(pageTitleQuery, Normalizer.Form.NFKD)
                    .replace("\\p{M}".toRegex(), "")

                val characterPages = characterPageRepository.read().map { characterPageEntity ->
                    CharacterPage().apply {
                        path = characterPageEntity.path
                        title = characterPageEntity.title
                        content = characterPageEntity.content
                        url = characterPageEntity.url
                        isRanking = characterPageEntity.isRanking
                    }
                }

                val pageInlineQueryResults = characterPages
                    .filter { characterPage ->
                        Normalizer.normalize(characterPage.title, Normalizer.Form.NFKD)
                            .replace("\\p{M}".toRegex(), "").contains(pageTitleQuery, ignoreCase = true)
                    }
                    .map { characterPage ->
                        InlineQueryResult.Article(
                            id = characterPage.path,
                            title = characterPage.title,
                            inputMessageContent = InputMessageContent.Text(characterPage.url),
                            description = games.first { game ->
                                game.code == characterPage.path.substringBefore("-")
                            }.name
                        )
                    }

                bot.answerInlineQuery(inlineQuery.id, pageInlineQueryResults)
            }
        }
    }

    rpgcBot.startPolling()
}