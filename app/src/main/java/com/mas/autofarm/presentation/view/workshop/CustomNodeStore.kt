package com.mas.autofarm.presentation.view.workshop

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 自定义节点模板存储：
 * - assets/custom_nodes/（内置示例，只读）
 * - filesDir/custom_nodes/（用户导入，优先）
 * 扫描后自动注册为工坊可用的自定义节点类型。
 */
object CustomNodeStore {

    private const val DIR_NAME = "custom_nodes"

    /** 列出全部模板（内置 + 用户），按名称排序。 */
    fun listAll(context: Context): List<CustomNodeTemplate> {
        val result = mutableListOf<CustomNodeTemplate>()
        // 内置（assets）
        runCatching {
            context.assets.list(DIR_NAME)?.forEach { name ->
                if (name.endsWith(".json")) {
                    context.assets.open("$DIR_NAME/$name").bufferedReader().use { r ->
                        runCatching {
                            result += Json.decodeFromString(CustomNodeTemplate.serializer(), r.readText())
                        }.onFailure { e ->
                            android.util.Log.w("CustomNodeStore", "内置模板解析失败 $name: ${e.message}")
                        }
                    }
                }
            }
        }
        // 用户目录（filesDir/custom_nodes）
        val userDir = File(context.filesDir, DIR_NAME)
        runCatching {
            userDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".json")) {
                    runCatching {
                        result += Json.decodeFromString(CustomNodeTemplate.serializer(), f.readText())
                    }.onFailure { e ->
                        android.util.Log.w("CustomNodeStore", "用户模板解析失败 ${f.name}: ${e.message}")
                    }
                }
            }
        }
        return result.sortedBy { it.name }
    }

    /** 按 id 查模板。 */
    fun find(context: Context, id: String): CustomNodeTemplate? =
        listAll(context).firstOrNull { it.id == id }

    /** 导入模板：把 json 内容写入用户目录（返回是否成功）。 */
    fun import(context: Context, jsonText: String): Boolean = runCatching {
        val tpl = Json.decodeFromString(CustomNodeTemplate.serializer(), jsonText)
        require(tpl.id.isNotBlank() && tpl.name.isNotBlank()) { "模板缺少 id/name" }
        val dir = File(context.filesDir, DIR_NAME)
        dir.mkdirs()
        val safeName = tpl.id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        File(dir, "$safeName.json").writeText(
            Json { prettyPrint = true }.encodeToString(CustomNodeTemplate.serializer(), tpl)
        )
        true
    }.getOrElse { false }

    /** 本地新建模板：名称/类型/执行命令（无参数版），返回是否成功。 */
    fun create(context: Context, name: String, category: String, command: String, description: String): Boolean = runCatching {
        require(name.isNotBlank() && command.isNotBlank()) { "名称与命令不能为空" }
        val id = "custom_" + (System.currentTimeMillis() % 100000000L)
        val tpl = CustomNodeTemplate(
            id = id, name = name, category = category,
            description = description.ifBlank { "本地自定义节点" },
            params = emptyList(), command = command,
        )
        val dir = File(context.filesDir, DIR_NAME)
        dir.mkdirs()
        File(dir, "$id.json").writeText(Json { prettyPrint = true }.encodeToString(CustomNodeTemplate.serializer(), tpl))
        true
    }.getOrDefault(false)

    /** 删除用户自定义模板（内置 assets 模板不可删）。 */
    fun delete(context: Context, id: String): Boolean = runCatching {
        val dir = File(context.filesDir, DIR_NAME)
        dir.listFiles()?.firstOrNull { f ->
            f.isFile && f.name.endsWith(".json") &&
                runCatching { Json.decodeFromString(CustomNodeTemplate.serializer(), f.readText()).id == id }.getOrDefault(false)
        }?.delete() ?: false
    }.getOrDefault(false)

    /** 导出模板为 JSON 文本（供保存文件/分享）。 */
    fun exportJson(context: Context, id: String): String? = find(context, id)?.let {
        Json { prettyPrint = true }.encodeToString(CustomNodeTemplate.serializer(), it)
    }

    /** 判断模板是否用户自建（可删）还是内置（只读）。 */
    fun isUserCustom(context: Context, id: String): Boolean = runCatching {
        val dir = File(context.filesDir, DIR_NAME)
        dir.listFiles()?.any { f ->
            f.isFile && f.name.endsWith(".json") &&
                runCatching { Json.decodeFromString(CustomNodeTemplate.serializer(), f.readText()).id == id }.getOrDefault(false)
        } ?: false
    }.getOrDefault(false)
}