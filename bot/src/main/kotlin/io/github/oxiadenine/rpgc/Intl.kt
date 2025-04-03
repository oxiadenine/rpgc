package io.github.oxiadenine.rpgc

import com.typesafe.config.ConfigFactory

class Intl(language: String = DEFAULT_LANGUAGE) {
    val languages = listOf(DEFAULT_LANGUAGE, "en")

    private val messages: Map<String, String>
    
    init {
        val messagesResource = if (languages.contains(language)) {
            "messages/messages-$language"
        } else "messages/messages-$DEFAULT_LANGUAGE"
        
        messages = ConfigFactory.load(messagesResource).entrySet().associate { entry ->
            entry.key to entry.value.unwrapped().toString()
        }
    }

    fun translate(id: String) = messages[id] ?: ""

    fun translate(id: String, value: Pair<String, String>) = messages[id]?.let { message ->
        if (message.contains(value.first)) {
            message.replace("{${value.first}}", value.second)
        } else message
    } ?: ""

    fun translate(id: String, values: List<Pair<String, String>>) = messages[id]?.let { message ->
        var translatedMessage = message

        values.forEach { value ->
            translatedMessage = if (translatedMessage.contains(value.first)) {
                translatedMessage.replace("{${value.first}}", value.second)
            } else translatedMessage
        }

        translatedMessage
    } ?: ""

    companion object {
        const val DEFAULT_LANGUAGE = "es"
    }
}