package ru.screenwriter.app

import android.content.Context
import org.json.JSONObject

/** Loads the bundled writing craft rules locally and selects a compact subset per writing mode. */
object WritingKnowledge {
    @Volatile private var cached: JSONObject? = null

    fun forMode(context: Context, mode: String): String = runCatching {
        val root = cached ?: synchronized(this) {
            cached ?: context.assets.open("writing_knowledge.json").bufferedReader(Charsets.UTF_8).use {
                JSONObject(it.readText()).also { parsed -> cached = parsed }
            }
        }
        val selectedIds = when (mode) {
            "Диалог" -> listOf("dialogue", "character_design", "prose")
            "Критик", "Редактор" -> listOf("prose", "pacing", "scene_design")
            "Сюжетный поворот" -> listOf("plot_causality", "scene_design", "pacing")
            "Проверить канон" -> listOf("plot_causality", "character_design", "scene_design")
            "Добавить героя" -> listOf("character_design", "dialogue", "plot_causality")
            else -> listOf("scene_design", "character_design", "plot_causality", "prose")
        }
        val modules = root.getJSONArray("modules")
        val lines = mutableListOf<String>()
        for (i in 0 until modules.length()) {
            val module = modules.getJSONObject(i)
            if (module.optString("id") !in selectedIds) continue
            val rules = module.optJSONArray("rules") ?: continue
            val count = minOf(3, rules.length())
            for (j in 0 until count) lines.add("• " + rules.optString(j))
        }
        lines.joinToString("\n")
    }.getOrDefault("")
}
