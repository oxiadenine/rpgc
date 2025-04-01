package io.github.oxiadenine.rpgc

import com.typesafe.config.ConfigFactory

class Intl(locale: String = DEFAULT_LOCALE) {
    val locales = listOf(DEFAULT_LOCALE, "en")

    private val messages: Map<String, String>
    
    init {
        val localeResource = if (locales.contains(locale)) {
            "locales/$locale"
        } else "locales/$DEFAULT_LOCALE"
        
        messages = ConfigFactory.load(localeResource).entrySet().associate { entry ->
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
        const val DEFAULT_LOCALE = "es"
    }
}