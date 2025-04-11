package io.github.oxiadenine.rpgc.bot

import com.typesafe.config.Config
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import dev.kord.rest.NamedFile
import io.github.oxiadenine.rpgc.bot.command.CharacterImageCommandHandler
import io.github.oxiadenine.rpgc.bot.command.Command
import io.github.oxiadenine.rpgc.bot.command.CommandResult
import io.github.oxiadenine.rpgc.common.repository.*
import io.ktor.client.request.forms.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class Application private constructor(
    val config: Config,
    override val coroutineContext: CoroutineContext = EmptyCoroutineContext
) : CoroutineScope {
    val log: Logger = LoggerFactory.getLogger(Application::class.java)

    companion object {
        fun create(config: Config, init: Application.() -> Unit) = Application(config).apply { init() }
    }
}

fun Application.bot(
    gameRepository: GameRepository,
    characterRepository: CharacterRepository,
    characterImageRepository: CharacterImageRepository
) {
    val characterImageCommandHandler = CharacterImageCommandHandler(
        gameRepository, characterRepository, characterImageRepository
    )

    runBlocking {
        val kord = Kord(config.getString("discord.token"))

        kord.on<MessageCreateEvent> {
            val command = try {
                Command.from(message.content)
            } catch (_: IllegalArgumentException) {
                return@on
            }

            when (val result = characterImageCommandHandler.handle(command)) {
                is CommandResult.Success -> {
                    message.channel.createMessage {
                        files.addAll(result.data.map { characterImage ->
                            NamedFile("${characterImage.name}.${characterImage.type}", ChannelProvider {
                                ByteReadChannel(characterImage.bytes)
                            })
                        })
                    }
                }
                is CommandResult.Failure -> {
                    result.exception?.let { exception ->
                        log.info(exception.stackTraceToString())
                    }
                }
            }
        }

        kord.login {
            @OptIn(PrivilegedIntent::class)
            intents += Intent.MessageContent
        }
    }
}