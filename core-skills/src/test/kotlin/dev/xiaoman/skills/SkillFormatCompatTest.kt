package dev.xiaoman.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SkillFormatCompatTest {
    /** Shape published by SkillHub: extra keys, block-scalar summary, slug instead of name. */
    private val skillHubMd = """
        ---
        slug: multi-search-engine
        name: 多引擎搜索
        displayName: 多引擎搜索
        version: 3.0.2
        description: |-
          提供 16 个搜索引擎的跨引擎检索
          与多源交叉验证。
        tags: [搜索, 效率]
        tools: [web]
        ---
        # 多引擎搜索

        先聚合检索，再去重。
    """.trimIndent()

    @Test
    fun normalizesSkillHubFrontmatter() {
        val rewritten = assertNotNull(SkillFormatCompat.normalize(skillHubMd, null))
        val (skill, problems) = SkillParser.parseFolder("multi-search-engine", rewritten, null, "skillhub")
        assertNotNull(skill)
        assertEquals(emptyList(), problems.map { it.message })
        assertEquals("multi-search-engine", skill.name)
        assertEquals("提供 16 个搜索引擎的跨引擎检索 与多源交叉验证。", skill.description)
        assertEquals(true, "先聚合检索，再去重。" in skill.instructions)
    }

    @Test
    fun fallsBackToRequestedSlugWhenNameIsUnusable() {
        val rewritten = assertNotNull(
            SkillFormatCompat.normalize(
                """
                ---
                name: 中文名字
                summary: 一个技能
                ---
                body
                """.trimIndent(),
                fallbackName = "fancy-skill",
            ),
        )
        assertEquals("fancy-skill", SkillParser.parseFolder("fancy-skill", rewritten, null, "skillhub").first?.name)
    }

    @Test
    fun rejectsTextWithoutFrontmatter() {
        assertNull(SkillFormatCompat.normalize("# 没有 frontmatter", null))
    }
}
