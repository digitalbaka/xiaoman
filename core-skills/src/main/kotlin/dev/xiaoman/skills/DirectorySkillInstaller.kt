package dev.xiaoman.skills

import dev.xiaoman.api.InstalledSkillInfo
import dev.xiaoman.api.SkillInstallResult
import dev.xiaoman.api.SkillInstaller
import dev.xiaoman.api.SkillProblem
import dev.xiaoman.api.SkillSafety
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Installs skills from a zip into `<skillsDir>/<skill-name>/`, next to bundled skills.
 *
 * Acks a zip whose root or one of its first levels contains `SKILL.md`; several skills in one
 * archive are installed together. Entries outside the temp folder (zip slip), hidden folders and
 * macOS metadata are skipped. A disabled skill keeps its files and gains a `.disabled` marker,
 * which [DirectorySkillRepository] ignores.
 */
class DirectorySkillInstaller(
    private val skillsDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SkillInstaller {

    override suspend fun installFromZip(bytes: ByteArray, sourceName: String): SkillInstallResult = withContext(io) {
        if (bytes.isEmpty()) return@withContext failure("文件是空的")
        if (bytes.size > MAX_ZIP_BYTES) return@withContext failure("安装包太大（上限 ${MAX_ZIP_BYTES / 1024 / 1024}MB）")
        skillsDir.mkdirs()
        val temp = File(skillsDir, ".install-${System.currentTimeMillis()}-${(0..999).random()}")
        if (!temp.mkdirs()) return@withContext failure("无法创建临时目录")
        try {
            val unzipError = runCatching { unzip(bytes, temp) }.exceptionOrNull()
            if (unzipError != null) return@withContext failure("zip 解析失败：${unzipError.message ?: unzipError.javaClass.simpleName}")
            val roots = findSkillRoots(temp)
            if (roots.isEmpty()) return@withContext failure("安装包里没有找到 SKILL.md")
            val installed = mutableListOf<String>()
            val replaced = mutableListOf<String>()
            val problems = mutableListOf<String>()
            for (root in roots) {
                val skillFile = File(root, "SKILL.md")
                val flowFile = File(root, "flow.yaml")
                val rawText = try {
                    skillFile.readText()
                } catch (e: Exception) {
                    problems += "${root.name}: 无法读取 SKILL.md（${e.message ?: "IO 错误"}）"
                    continue
                }
                val flowYaml = if (flowFile.isFile) runCatching { flowFile.readText() }.getOrNull() else null
                // SkillHub folders often sit at the zip root, so the folder name is a temp name;
                // prefer the requested slug for the folder-name check.
                val parseName = sourceName.takeIf { SkillParser.NAME.matches(it) } ?: root.name
                var parsed = runCatching {
                    SkillParser.parseFolder(
                        name = parseName,
                        skillMd = rawText,
                        flowYaml = flowYaml,
                        source = sourceName,
                    )
                }.getOrElse { null to listOf(SkillProblem(root.path, it.message ?: "无法读取")) }
                var normalized: String? = null
                if (parsed.first == null) {
                    // Wider Agent Skills frontmatter (SkillHub) is rewritten to our strict schema.
                    val rewritten = runCatching { SkillFormatCompat.normalize(rawText, parseName) }.getOrNull()
                    if (rewritten != null) {
                        val retried = runCatching {
                            SkillParser.parseFolder(
                                name = parseName,
                                skillMd = rewritten,
                                flowYaml = flowYaml,
                                source = sourceName,
                            )
                        }.getOrElse { null to emptyList() }
                        if (retried.first != null) {
                            parsed = retried
                            normalized = rewritten
                        }
                    }
                }
                val (skill, warnings) = parsed
                if (skill == null) {
                    problems += "${root.name}: ${warnings.firstOrNull()?.message ?: "SKILL.md 不合法"}"
                    continue
                }
                if (!SkillParser.NAME.matches(skill.name)) {
                    problems += "${root.name}: 技能名必须是 [a-z0-9-]"
                    continue
                }
                val target = File(skillsDir, skill.name)
                if (!inside(target, skillsDir)) {
                    problems += "${root.name}: 目标路径不合法"
                    continue
                }
                val existed = target.exists()
                if (existed && !target.deleteRecursively()) {
                    problems += "${skill.name}: 无法覆盖已有技能"
                    continue
                }
                if (!root.copyRecursively(target, overwrite = true)) {
                    problems += "${skill.name}: 写入失败"
                    continue
                }
                normalized?.let { File(target, "SKILL.md").writeText(it) }
                installed += skill.name
                if (existed) replaced += skill.name
                warnings.firstOrNull()?.let { problems += "${skill.name}: ${it.message}" }
            }
            when {
                installed.isEmpty() -> failure(problems.joinToString("；").ifBlank { "没有可安装的技能" })
                else -> SkillInstallResult(
                    ok = true,
                    message = buildString {
                        append("已安装 ").append(installed.joinToString("、"))
                        if (replaced.isNotEmpty()) append("（覆盖 ").append(replaced.joinToString("、")).append("）")
                        if (problems.isNotEmpty()) append("；部分提示：").append(problems.joinToString("；"))
                    },
                    installed = installed,
                    replaced = replaced,
                )
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    override suspend fun installFromMarkdown(name: String, markdown: String, replace: Boolean): SkillInstallResult = withContext(io) {
        if (!SkillParser.NAME.matches(name)) return@withContext failure("技能名必须是 [a-z0-9-]")
        val secrets = SkillSafety.problems(markdown)
        if (secrets.isNotEmpty()) {
            return@withContext failure("技能内容不能包含${secrets.joinToString("、")}，请删掉这些信息后重试")
        }
        if (markdown.isBlank()) return@withContext failure("SKILL.md 是空的")
        skillsDir.mkdirs()
        val target = File(skillsDir, name)
        if (!inside(target, skillsDir)) return@withContext failure("目标路径不合法")
        if (target.exists() && !replace) return@withContext failure("已经有一个叫 $name 的技能，覆盖它需要先确认")
        val parsed = runCatching {
            SkillParser.parseFolder(name, markdown, null, "chat")
        }.getOrElse { null to listOf(SkillProblem("SKILL.md", it.message ?: "无法读取")) }
        val (skill, warnings) = parsed
        if (skill == null) {
            return@withContext failure(warnings.firstOrNull()?.message ?: "SKILL.md 不合法")
        }
        if (!target.mkdirs() && !target.isDirectory) return@withContext failure("无法创建技能目录")
        return@withContext try {
            File(target, "SKILL.md").writeText(markdown)
            SkillInstallResult(
                ok = true,
                message = if (replace) "已更新技能 ${skill.name}" else "已添加技能 ${skill.name}",
                installed = listOf(skill.name),
                replaced = if (replace) listOf(skill.name) else emptyList(),
            )
        } catch (e: Exception) {
            failure("写入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    override suspend fun installFromUrl(url: String): SkillInstallResult = withContext(io) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            return@withContext failure("只支持 http/https 链接")
        }
        val bytes = try {
            download(trimmed)
        } catch (e: Exception) {
            return@withContext failure("下载失败：${e.message ?: e.javaClass.simpleName}")
        }
        installFromZip(bytes, trimmed.substringAfterLast('/').substringBefore('?').ifBlank { "url" })
    }

    override suspend fun listInstalled(): List<InstalledSkillInfo> = withContext(io) {
        skillsDir.mkdirs()
        skillsDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?.map { dir ->
                val skillFile = File(dir, "SKILL.md")
                val flowFile = File(dir, "flow.yaml")
                val parsed = if (skillFile.isFile) {
                    runCatching {
                        SkillParser.parseFolder(
                            name = dir.name,
                            skillMd = skillFile.readText(),
                            flowYaml = if (flowFile.isFile) flowFile.readText() else null,
                            source = dir.path,
                        ).first
                    }.getOrNull()
                } else {
                    null
                }
                InstalledSkillInfo(
                    name = parsed?.name ?: dir.name,
                    description = parsed?.description ?: if (skillFile.isFile) "（无法解析 SKILL.md）" else "（缺少 SKILL.md）",
                    enabled = !File(dir, ".disabled").exists(),
                    source = dir.path,
                )
            }
            ?: emptyList()
    }

    override suspend fun setEnabled(name: String, enabled: Boolean): Boolean = withContext(io) {
        if (!SkillParser.NAME.matches(name)) return@withContext false
        val dir = File(skillsDir, name)
        if (!dir.isDirectory) return@withContext false
        val marker = File(dir, ".disabled")
        if (enabled) {
            !marker.exists() || marker.delete()
        } else {
            runCatching { marker.createNewFile() }.getOrDefault(false)
        }
    }

    override suspend fun delete(name: String): Boolean = withContext(io) {
        if (!SkillParser.NAME.matches(name)) return@withContext false
        val dir = File(skillsDir, name)
        if (!dir.isDirectory || !inside(dir, skillsDir)) return@withContext false
        dir.deleteRecursively()
    }

    private fun findSkillRoots(temp: File): List<File> {
        val result = mutableListOf<File>()
        fun visit(dir: File, depth: Int) {
            if (depth > MAX_SKILL_DEPTH) return
            if (File(dir, "SKILL.md").isFile) {
                result += dir
                return
            }
            dir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in IGNORED }
                ?.forEach { visit(it, depth + 1) }
        }
        visit(temp, 0)
        return result
    }

    private fun unzip(bytes: ByteArray, temp: File) {
        val rootPath = temp.canonicalPath
        var total = 0L
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw IOException("文件太多（上限 $MAX_ENTRIES）")
                val name = entry.name.replace('\\', '/')
                val ignored = name.startsWith("/") || name.split('/').any { it == ".." } ||
                    name.startsWith("__MACOSX/") || name.substringAfterLast('/').startsWith(".")
                if (ignored) continue
                val out = File(temp, name).canonicalFile
                if (out.path != rootPath && !out.path.startsWith(rootPath + File.separator)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
                total += out.length()
                if (total > MAX_UNZIPPED_BYTES) throw IOException("解压后太大（上限 ${MAX_UNZIPPED_BYTES / 1024 / 1024}MB）")
            }
        }
    }

    private fun download(url: String): ByteArray {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Xiaoman-Skill-Installer/1.0")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    current = connection.getHeaderField("Location") ?: throw IOException("HTTP $code 缺少跳转地址")
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val out = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ZIP_BYTES) throw IOException("安装包太大（上限 ${MAX_ZIP_BYTES / 1024 / 1024}MB）")
                        out.write(buffer, 0, n)
                    }
                }
                return out.toByteArray()
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("跳转次数过多")
    }

    private fun inside(file: File, root: File): Boolean {
        val path = file.canonicalPath
        val base = root.canonicalPath
        return path == base || path.startsWith(base + File.separator)
    }

    private fun failure(message: String) = SkillInstallResult(false, message)

    private companion object {
        const val MAX_ZIP_BYTES = 20L * 1024 * 1024
        const val MAX_UNZIPPED_BYTES = 60L * 1024 * 1024
        const val MAX_ENTRIES = 2_000
        const val MAX_SKILL_DEPTH = 3
        const val MAX_REDIRECTS = 5
        val IGNORED = setOf("__MACOSX", ".git", "node_modules")
    }
}
