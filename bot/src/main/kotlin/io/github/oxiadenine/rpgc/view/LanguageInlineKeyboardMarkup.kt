package io.github.oxiadenine.rpgc.view

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import io.github.oxiadenine.rpgc.repository.User

data class Language(val name: String, val locale: String)

object LanguageInlineKeyboardMarkup {
    fun create(user: User, languages: List<Language>) = InlineKeyboardMarkup.create(languages.map { language ->
        val isUserLanguage = language.locale == user.language

        listOf(InlineKeyboardButton.CallbackData(
            text = if (isUserLanguage) "${language.name} \u2705" else language.name,
            callbackData = language.locale)
        )
    })
}