package dev.xiaoman.skills

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SKILL_MD = """---
name: daily-review
description: 每日复盘
---
每天晚上问用户今天做成了哪三件事。"""

class DirectorySkillInstallerTest {
    private fun tempDir() = Files.createTempDirectory("skills").toFile()

    @Test
    fun markdownInstallWritesSkillFolder() = runTest {
        val dir = tempDir()
        val installer = DirectorySkillInstaller(dir)

        val result = installer.installFromMarkdown("daily-review", SKILL_MD)

        assertTrue(result.ok, result.message)
        assertEquals("daily-review", installer.listInstalled().single().name)
        assertTrue(java.io.File(dir, "daily-review/SKILL.md").readText().contains("每日复盘"))
    }

    @Test
    fun secretsAreRefused() = runTest {
        val dir = tempDir()
        val installer = DirectorySkillInstaller(dir)

        val result = installer.installFromMarkdown("leaky", SKILL_MD.replace("每天晚上", "手机号 13800138000，每天晚上"))

        assertFalse(result.ok)
        assertTrue(result.message.contains("手机号"))
        assertTrue(installer.listInstalled().isEmpty())
    }

    @Test
    fun overwriteNeedsReplaceFlag() = runTest {
        val dir = tempDir()
        val installer = DirectorySkillInstaller(dir)
        installer.installFromMarkdown("daily-review", SKILL_MD)

        val blocked = installer.installFromMarkdown("daily-review", SKILL_MD)
        assertFalse(blocked.ok)
        assertTrue(blocked.message.contains("已经有"))

        val replaced = installer.installFromMarkdown("daily-review", SKILL_MD.replace("三件事", "两件事"), replace = true)
        assertTrue(replaced.ok, replaced.message)
        assertTrue(java.io.File(dir, "daily-review/SKILL.md").readText().contains("两件事"))
    }

    @Test
    fun invalidNameIsRefused() = runTest {
        val result = DirectorySkillInstaller(tempDir()).installFromMarkdown("Daily Review", SKILL_MD)

        assertFalse(result.ok)
    }
}
