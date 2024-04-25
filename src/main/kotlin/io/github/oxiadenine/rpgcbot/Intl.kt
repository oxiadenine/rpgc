package io.github.oxiadenine.rpgcbot

import com.typesafe.config.ConfigFactory

class Intl(locale: String) {
    companion object {
        val DEFAULT_LOCALE = Language.SPANISH.locale
    }

    enum class Language(val locale: String) {
        ENGLISH("en"),
        SPANISH("es");

        override fun toString() = name.lowercase().replaceFirstChar { it.uppercase() }
    }
    
    private val messages: Map<String, String>
    
    init {
        val locales = Language.entries.map { language -> language.locale }
        
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
}