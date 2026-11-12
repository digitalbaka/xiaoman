package dev.xiaoman.skills

import org.snakeyaml.engine.v2.nodes.MappingNode

/**
 * Converts a `SKILL.md` written for the wider Agent Skills schema (as published on SkillHub:
 * `slug`/`version`/`tags`/`tools` keys, multi-line `summary`/`description`) into the strict
 * frontmatter this app understands. Only `name` and `description` survive; the body is kept.
 *
 * Returns `null` when the text cannot be converted (no frontmatter, broken YAML, no usable name
 * or description), so the caller can report the original parse problems.
 */
internal object SkillFormatCompat {
    private val WHITESPACE = Regex("\\s+")

    fun normalize(skillMd: String, fallbackName: String?): String? {
        val split = SkillParser.splitFrontmatter(skillMd) ?: return null
        val (yaml, body) = split
        val doc = YamlDoc("SKILL.md")
        val root = doc.compose(yaml) as? MappingNode ?: return null
        val entries = doc.entries(root)
        if (doc.problems.isNotEmpty()) return null

        val name = sequenceOf("slug", "name")
            .mapNotNull { key -> entries[key]?.let(doc::string)?.trim() }
            .map { it.substringAfterLast('/').removePrefix("@") }
            .firstOrNull { SkillParser.NAME.matches(it) }
            ?: fallbackName?.trim()?.takeIf { SkillParser.NAME.matches(it) }
            ?: return null

        val description = sequenceOf("summary", "description_zh", "description", "displayName")
            .mapNotNull { key -> entries[key]?.let(doc::string)?.let(::flatten) }
            .firstOrNull { it.isNotEmpty() }
            ?: return null

        return buildString {
            append("---\n")
            append("name: ").append(name).append('\n')
            append("description: \"").append(escape(description.take(MAX_DESCRIPTION))).append("\"\n")
            append("---\n\n")
            append(body)
            append('\n')
        }
    }

    private fun flatten(value: String): String = WHITESPACE.replace(value, " ").trim()

    private fun escape(value: String): String = buildString {
        for (ch in value) {
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch.code < 0x20 -> Unit
                else -> append(ch)
            }
        }
    }

    private const val MAX_DESCRIPTION = 500
}
