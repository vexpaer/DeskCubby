package com.deskcubby.app.ui.home

import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.DEFAULT_HOME_GREETINGS
import com.deskcubby.app.data.model.HomeGreetingTemplate
import java.time.LocalDate

/**
 * Date-stable home greetings. User-provided templates keep their order and may use {name}.
 */
internal object HomeGreeting {
    private const val NAME_TOKEN = "{name}"

    internal val templateCount: Int
        get() = DEFAULT_HOME_GREETINGS.size

    fun forDate(
        date: LocalDate,
        language: AppLanguage,
        userName: String,
        templates: List<HomeGreetingTemplate> = DEFAULT_HOME_GREETINGS,
    ): String {
        val patterns = templates.mapNotNull { template ->
            val preferred = if (language == AppLanguage.ENGLISH) {
                template.english
            } else {
                template.chinese
            }
            val fallback = if (language == AppLanguage.ENGLISH) {
                template.chinese
            } else {
                template.english
            }
            preferred.trim().ifBlank { fallback.trim() }.takeIf(String::isNotBlank)
        }
        if (patterns.isEmpty()) {
            return if (language == AppLanguage.ENGLISH) "Today's overview" else "今日概览"
        }
        val index = Math.floorMod(date.toEpochDay(), patterns.size.toLong()).toInt()
        val pattern = patterns[index]
        val name = userName.trim().ifBlank {
            if (language == AppLanguage.ENGLISH) "you" else "你"
        }
        return pattern.replace(NAME_TOKEN, name)
    }
}
