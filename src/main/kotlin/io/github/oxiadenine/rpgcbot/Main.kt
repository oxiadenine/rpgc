package io.github.oxiadenine.rpgcbot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.*
import com.github.kotlintelegrambot.entities.ChatId

fun main() {
    val rpgcBot = bot {
        token = System.getenv("TELEGRAM_BOT_TOKEN") ?: ""

        dispatch {
            command("start") {
                bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "RPGc Bot")
            }
        }
    }

    rpgcBot.startPolling()
}