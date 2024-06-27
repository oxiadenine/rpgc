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
    NEWCHARPAGE,
    EDITCHARPAGE,
    NEWCHARRANKPAGE,
    EDITCHARRANKPAGE,
    CANCEL
}

data class Game(val key: String, val name: String)

class UnauthorizedError : Error()

fun String.normalize() = Normalizer.normalize(this, Normalizer.Form.NFKD).replace("\\p{M}".toRegex(), "")

fun main() {
    val config = ConfigFactory.load()

    val botConfig = config.getConfig("bot")
    val telegraphConfig = config.getConfig("telegraph")
    val databaseConfig = config.getConfig("database")

    val userIds = botConfig.getString("userWhitelist").split(",").map { userId -> userId.toLong() }
    val games = botConfig.getString("gameList").split(",").map { gameName -> Game(
        key = gameName.lowercase().split(" ").joinToString("") { "${it[0]}" },
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
                            val command = currentCommandMap[userId] ?: return@message

                            currentCharacterPageMap.remove(userId)
                            currentGameMap.remove(userId)
                            currentCommandMap.remove(userId)

                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = intl.translate(
                                    id = "command.cancel.message",
                                    value = "command" to command.name.lowercase()
                                ),
                                replyMarkup = ReplyKeyboardRemove()
                            )
                        }
                        else -> return@message
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

                val command = currentCommandMap[userId] ?: return@message
                val game = currentGameMap[userId] ?: return@message

                val characterPage = currentCharacterPageMap[userId] ?: return@message

                if (characterPage.content.value.isNotEmpty()) return@message

                runCatching {
                    if (characterPage.title.value.isEmpty()) {
                        val characterPageTitle = CharacterPage.Title(message.text!!)

                        val characterPageEntities = characterPageRepository.read()

                        when (command) {
                            Command.NEWCHARPAGE, Command.NEWCHARRANKPAGE -> {
                                val characterPageIsRanking = command.name == Command.NEWCHARRANKPAGE.name

                                val characterPageEntityExists = if (characterPageIsRanking) {
                                    characterPageEntities.filter { characterPageEntity ->
                                        characterPageEntity.path.contains(game.key) && characterPageEntity.isRanking
                                    }
                                } else {
                                    characterPageEntities.filter { characterPageEntity ->
                                        characterPageEntity.path.contains(game.key) && !characterPageEntity.isRanking
                                    }
                                }.any { characterPageEntity ->
                                    characterPageEntity.title.normalize().equals(
                                        other = characterPageTitle.value.normalize(),
                                        ignoreCase = true
                                    )
                                }

                                if (characterPageEntityExists) {
                                    throw CharacterPage.Title.ExistsError()
                                }

                                characterPage.title = characterPageTitle
                                characterPage.isRanking = characterPageIsRanking

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.new.character.page.content.message",
                                        value = "title" to characterPage.title.value
                                    )
                                )
                            }
                            Command.EDITCHARPAGE, Command.EDITCHARRANKPAGE -> {
                                val characterPageEntity = if (command.name == Command.EDITCHARRANKPAGE.name) {
                                    characterPageEntities.filter { characterPageEntity ->
                                        characterPageEntity.path.contains(game.key) && characterPageEntity.isRanking
                                    }
                                } else {
                                    characterPageEntities.filter { characterPageEntity ->
                                        characterPageEntity.path.contains(game.key) && !characterPageEntity.isRanking
                                    }
                                }.firstOrNull { characterPageEntity ->
                                    characterPageEntity.title.normalize().equals(
                                        other = characterPageTitle.value.normalize(),
                                        ignoreCase = true
                                    )
                                } ?: return@message

                                characterPage.path = characterPageEntity.path
                                characterPage.title = CharacterPage.Title(characterPageEntity.title)
                                characterPage.url = characterPageEntity.url
                                characterPage.isRanking = characterPageEntity.isRanking

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.edit.character.page.content.message1",
                                        value = "title" to characterPage.title.value
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

                        characterPage.content = CharacterPage.Content(characterPageContentJson)

                        when (command) {
                            Command.NEWCHARPAGE -> {
                                val page = telegraphApi.createPage(TelegraphApi.CreatePage(
                                    accessToken = telegraphConfig.getString("accessToken"),
                                    title = buildString {
                                        append("${game.key}-")
                                        append(characterPage.title.value.normalize().lowercase().replace(" ", "-"))
                                    },
                                    authorName = telegraphConfig.getString("username"),
                                    content = characterPage.content.value
                                )).getOrThrow()

                                characterPage.path = page.path
                                characterPage.url = page.url

                                telegraphApi.editPage(characterPage.path, TelegraphApi.EditPage(
                                    accessToken = telegraphConfig.getString("accessToken"),
                                    title = characterPage.title.value,
                                    content = characterPage.content.value,
                                    authorName = telegraphConfig.getString("username")
                                )).getOrThrow()

                                val characterPageEntity = CharacterPageEntity(
                                    path = characterPage.path,
                                    title = characterPage.title.value,
                                    content = characterPage.content.value,
                                    url = characterPage.url,
                                    isRanking = characterPage.isRanking
                                )

                                characterPageRepository.create(characterPageEntity)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.new.character.page.success.message",
                                        value = "title" to characterPage.title.value
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
                                telegraphApi.editPage(characterPage.path, TelegraphApi.EditPage(
                                    accessToken = telegraphConfig.getString("accessToken"),
                                    title = characterPage.title.value,
                                    content = characterPage.content.value,
                                    authorName = telegraphConfig.getString("username")
                                )).getOrThrow()

                                val characterPageEntity = CharacterPageEntity(
                                    path = characterPage.path,
                                    title = characterPage.title.value,
                                    content = characterPage.content.value,
                                    url = characterPage.url,
                                    isRanking = characterPage.isRanking
                                )

                                characterPageRepository.update(characterPageEntity)

                                bot.sendMessage(
                                    chatId = ChatId.fromId(userId),
                                    text = intl.translate(
                                        id = "command.edit.character.page.success.message",
                                        value = "title" to characterPage.title.value
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
                    when(error) {
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

                val command = currentCommandMap[userId] ?: return@message
                val game = currentGameMap[userId] ?: return@message

                val characterPage = currentCharacterPageMap[userId] ?: return@message

                if (characterPage.content.value.isEmpty()) return@message

                runCatching {
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

                    val characterPageContentImageJson = Json.encodeToString(buildJsonArray {
                        addAll(Json.decodeFromString<JsonArray>(characterPage.content.value))
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

                    characterPage.content = CharacterPage.Content(characterPageContentImageJson)

                    when (command) {
                        Command.NEWCHARRANKPAGE -> {
                            val page = telegraphApi.createPage(TelegraphApi.CreatePage(
                                accessToken = telegraphConfig.getString("accessToken"),
                                title = buildString {
                                    append("${game.key}-")
                                    append(characterPage.title.value.normalize().lowercase().replace(" ", "-"))
                                    append("=${CharacterPage.Paths.RANKING.name.lowercase()}")
                                },
                                authorName = telegraphConfig.getString("username"),
                                content = characterPage.content.value
                            )).getOrThrow()

                            characterPage.path = page.path
                            characterPage.url = page.url

                            telegraphApi.editPage(characterPage.path, TelegraphApi.EditPage(
                                accessToken = telegraphConfig.getString("accessToken"),
                                title = characterPage.title.value,
                                content = characterPage.content.value,
                                authorName = telegraphConfig.getString("username")
                            )).getOrThrow()

                            val characterPageEntity = CharacterPageEntity(
                                path = characterPage.path,
                                title = characterPage.title.value,
                                content = characterPage.content.value,
                                url = characterPage.url,
                                isRanking = characterPage.isRanking,
                                image = characterPage.image
                            )

                            characterPageRepository.create(characterPageEntity)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(
                                    id = "command.new.character.page.success.message",
                                    value = "title" to characterPage.title.value
                                )
                            )
                        }
                        Command.EDITCHARRANKPAGE -> {
                            telegraphApi.editPage(characterPage.path, TelegraphApi.EditPage(
                                accessToken = telegraphConfig.getString("accessToken"),
                                title = characterPage.title.value,
                                content = characterPage.content.value,
                                authorName = telegraphConfig.getString("username")
                            )).getOrThrow()

                            val characterPageEntity = CharacterPageEntity(
                                path = characterPage.path,
                                title = characterPage.title.value,
                                content = characterPage.content.value,
                                url = characterPage.url,
                                isRanking = characterPage.isRanking,
                                image = characterPage.image
                            )

                            characterPageRepository.update(characterPageEntity)

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(
                                    id = "command.edit.character.page.success.message",
                                    value = "title" to characterPage.title.value
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

                val command = currentCommandMap[userId] ?: return@callbackQuery

                runCatching {
                    val game = games.first { game -> game.key == callbackQuery.data }

                    currentCharacterPageMap[userId] = CharacterPage()

                    when (command) {
                        Command.NEWCHARPAGE, Command.NEWCHARRANKPAGE -> {
                            currentGameMap[userId] = game

                            bot.sendMessage(
                                chatId = ChatId.fromId(userId),
                                text = intl.translate(id = "command.new.character.page.title.message")
                            )
                        }
                        Command.EDITCHARPAGE, Command.EDITCHARRANKPAGE -> {
                            val characterPageEntities = characterPageRepository.read()

                            val characterPages = if (command.name == Command.EDITCHARRANKPAGE.name) {
                                characterPageEntities.filter { characterPageEntity ->
                                    characterPageEntity.path.contains(game.key) && characterPageEntity.isRanking
                                }
                            } else {
                                characterPageEntities.filter { characterPageEntity ->
                                    characterPageEntity.path.contains(game.key) && !characterPageEntity.isRanking
                                }
                            }.map { characterPageEntity ->
                                CharacterPage().apply {
                                    path = characterPageEntity.path
                                    title = CharacterPage.Title(characterPageEntity.title)
                                    content = CharacterPage.Content(characterPageEntity.content)
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

            inlineQuery {
                val pageTitleQuery = inlineQuery.query

                if (pageTitleQuery.isBlank() or pageTitleQuery.isEmpty()) return@inlineQuery

                val characterPageEntities = characterPageRepository.read()

                val pageInlineQueryResults = characterPageEntities
                    .filter { characterPageEntity ->
                        characterPageEntity.title.normalize().contains(pageTitleQuery.normalize(), ignoreCase = true)
                    }
                    .map { characterPageEntity ->
                        InlineQueryResult.Article(
                            id = characterPageEntity.path,
                            title = characterPageEntity.title,
                            inputMessageContent = InputMessageContent.Text(characterPageEntity.url),
                            description = games.first { game ->
                                game.key == characterPageEntity.path.substringBefore("-")
                            }.name
                        )
                    }

                bot.answerInlineQuery(inlineQuery.id, pageInlineQueryResults)
            }
        }
    }

    rpgcBot.startPolling()
}