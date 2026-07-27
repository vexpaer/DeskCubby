package com.deskcubby.app.ui.home

import com.deskcubby.app.data.model.AppLanguage
import java.time.LocalDate

/**
 * Date-stable home greetings. A local date always maps to the same bilingual template, while
 * consecutive days walk through the full set before repeating.
 */
internal object HomeGreeting {
    private const val NAME_TOKEN = "{name}"

    private data class Template(
        val chinese: String,
        val english: String,
    )

    private val templates = listOf(
        Template("${NAME_TOKEN}，愿今天有个轻盈的开始！", "${NAME_TOKEN}, here's to a light and easy start!"),
        Template("新的一天，给${NAME_TOKEN}一点好心情！", "A new day, and a little brightness for ${NAME_TOKEN}!"),
        Template("${NAME_TOKEN}，今天也慢慢来吧。", "${NAME_TOKEN}, take today one step at a time."),
        Template("愿${NAME_TOKEN}今天遇见小小的惊喜。", "May ${NAME_TOKEN} find a small surprise today."),
        Template("${NAME_TOKEN}，把今天过成喜欢的样子！", "${NAME_TOKEN}, shape today into something enjoyable!"),
        Template("今天也为${NAME_TOKEN}留一点从容。", "May today leave a little breathing room for ${NAME_TOKEN}."),
        Template("${NAME_TOKEN}，别忘了欣赏沿途的风景。", "${NAME_TOKEN}, remember to enjoy the view along the way."),
        Template("愿${NAME_TOKEN}今天灵感满满！", "May inspiration find ${NAME_TOKEN} today!"),
        Template("${NAME_TOKEN}，今天值得期待。", "${NAME_TOKEN}, today is worth looking forward to."),
        Template("给${NAME_TOKEN}的今日份能量已送达！", "Today's boost for ${NAME_TOKEN} has arrived!"),
        Template("${NAME_TOKEN}，愿忙碌里也有片刻安静。", "${NAME_TOKEN}, may there be calm among the busy moments."),
        Template("今天的${NAME_TOKEN}也在闪闪发光！", "${NAME_TOKEN} is shining today, too!"),
        Template("${NAME_TOKEN}，先喝口水，再向前走。", "${NAME_TOKEN}, take a sip of water, then keep going."),
        Template("愿${NAME_TOKEN}今天做成一件开心的小事。", "May ${NAME_TOKEN} finish one small, joyful thing today."),
        Template("${NAME_TOKEN}，给今天留一点想象力。", "${NAME_TOKEN}, leave a little room for imagination today."),
        Template("新的一页，等${NAME_TOKEN}来写。", "A fresh page is waiting for ${NAME_TOKEN}."),
        Template("${NAME_TOKEN}，愿今天比昨天更自在。", "${NAME_TOKEN}, may today feel easier than yesterday."),
        Template("今天也请${NAME_TOKEN}好好照顾自己。", "A gentle reminder for ${NAME_TOKEN}: take good care today."),
        Template("${NAME_TOKEN}，把重要的事轻轻放在心上。", "${NAME_TOKEN}, keep what matters close today."),
        Template("愿${NAME_TOKEN}今天收获一点确定的快乐。", "May ${NAME_TOKEN} find one sure bit of joy today."),
        Template("${NAME_TOKEN}，不必完美，向前一点就很好。", "${NAME_TOKEN}, no need for perfection—a little progress is enough."),
        Template("今天也给${NAME_TOKEN}一个温柔的拥抱。", "Sending ${NAME_TOKEN} a gentle hug for today."),
        Template("${NAME_TOKEN}，愿此刻成为好一天的开头。", "${NAME_TOKEN}, may this moment begin a good day."),
        Template("准备好了吗，${NAME_TOKEN}？今天会有新故事。", "Ready, ${NAME_TOKEN}? Today has a new story."),
    )

    internal val templateCount: Int
        get() = templates.size

    fun forDate(date: LocalDate, language: AppLanguage, userName: String): String {
        val index = Math.floorMod(date.toEpochDay(), templates.size.toLong()).toInt()
        val template = templates[index]
        val name = userName.trim().ifBlank {
            if (language == AppLanguage.ENGLISH) "Friend" else "朋友"
        }
        val pattern = if (language == AppLanguage.ENGLISH) template.english else template.chinese
        return pattern.replace(NAME_TOKEN, name)
    }
}
