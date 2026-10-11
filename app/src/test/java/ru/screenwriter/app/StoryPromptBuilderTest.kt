package ru.screenwriter.app

import org.junit.Assert.assertTrue
import org.junit.Test

class StoryPromptBuilderTest {
    @Test
    fun includesEverySavedSceneAndItsAuthorCommand() {
        val story = Story(
            title = "Дом у реки",
            world = "Город, где запрещены зеркала",
            setting = "Параноидальная атмосфера",
            genre = "Мистический триллер",
            characters = "Анна — архивистка",
            events = "Пропала красная книга",
            scenes = mutableListOf(
                Scene("Пусть найдёт письмо", "Анна нашла письмо под дверью.", "Сцена"),
                Scene("Пусть услышит шаги", "За стеной трижды скрипнула половица.", "Сцена")
            )
        )

        val prompt = StoryPromptBuilder.systemPrompt(story, "Продолжить")

        assertTrue(prompt.contains("Город, где запрещены зеркала"))
        assertTrue(prompt.contains("Анна — архивистка"))
        assertTrue(prompt.contains("Пропала красная книга"))
        assertTrue(prompt.contains("Анна нашла письмо под дверью."))
        assertTrue(prompt.contains("За стеной трижды скрипнула половица."))
        assertTrue(prompt.contains("Пусть найдёт письмо"))
        assertTrue(prompt.contains("Пусть услышит шаги"))
    }

    @Test
    fun emptyStoryStillProducesValidContext() {
        val prompt = StoryPromptBuilder.systemPrompt(Story(title = "Черновик"), "Продолжить")
        assertTrue(prompt.contains("Черновик"))
        assertTrue(prompt.contains("[Сцен пока нет]"))
    }

    @Test
    fun eachWritingModeHasAnExplicitInstruction() {
        val story = Story(title = "Проверка")
        assertTrue(StoryPromptBuilder.systemPrompt(story, "Критик").contains("редакторскую критику"))
        assertTrue(StoryPromptBuilder.systemPrompt(story, "Диалог").contains("живой диалог"))
        assertTrue(StoryPromptBuilder.systemPrompt(story, "Проверить канон").contains("Проверь противоречия"))
    }

    @Test
    fun contextIncludesRelevantOlderSceneForContinuity() {
        val scenes = (1..12).map { number ->
            Scene("Событие $number", if (number == 2) "В старом архиве спрятан красный ключ, который открывает дверь." else "Сцена $number: обычные события.", "Сцена")
        }.toMutableList()
        val story = Story(title = "Архив", scenes = scenes)
        val prompt = StoryPromptBuilder.generationPrompt(story, "Продолжить", "Найди красный ключ")
        assertTrue(prompt.contains("красный ключ"))
        assertTrue(prompt.contains("КОМАНДА АВТОРА"))
    }
}
