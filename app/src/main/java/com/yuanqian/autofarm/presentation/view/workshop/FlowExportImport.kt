package com.yuanqian.autofarm.presentation.view.workshop

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * 流程导出/导入（文件夹格式）：
 *
 * 导出目录结构：
 *   <流程名>/
 *   ├── project.json          # 流程（FlowProject）
 *   ├── custom_nodes/(json)   # 流程用到的自定义节点模板
 *   └── templates/(png)       # 流程用到的识别模板图片
 *
 * 导入：选整个文件夹 → 自动加载流程 / 自定义节点 / 模板图片。
 * 目录遍历用 Android 自带 DocumentsContract（无外部依赖）。
 */
object FlowExportImport {

    /** 一键导出全部：所有流程 + 全部模板图 + 全部自定义节点，打包到 Download/MASS导出/全部导出_时间戳/ */
    fun exportAll(context: Context): File? = runCatching {
        val exportRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "MASS导出",
        )
        exportRoot.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val dir = File(exportRoot, "全部导出_$stamp")
        dir.mkdirs()

        // 1. 所有流程（workshop/*/project.json → flows/<流程名>/project.json）
        val flowRoot = File(dir, "flows").apply { mkdirs() }
        File(context.filesDir, "workshop").listFiles()?.filter { it.isDirectory }?.forEach { flowDir ->
            val pj = File(flowDir, "project.json")
            if (pj.exists()) {
                val out = File(flowRoot, flowDir.name).apply { mkdirs() }
                pj.copyTo(File(out, "project.json"), overwrite = true)
            }
        }

        // 2. 全部模板图
        val tplDir = File(dir, "templates").apply { mkdirs() }
        TemplateStore.listAll(context).forEach { t ->
            runCatching { File(t.file).copyTo(File(tplDir, "${t.id}.png"), overwrite = true) }
        }

        // 3. 全部自定义节点
        val customDir = File(dir, "custom_nodes").apply { mkdirs() }
        CustomNodeStore.listAll(context).forEach { tpl ->
            File(customDir, "${tpl.id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")}.json").writeText(
                kotlinx.serialization.json.Json { prettyPrint = true }
                    .encodeToString(CustomNodeTemplate.serializer(), tpl)
            )
        }

        dir
    }.getOrNull()

    /** 导出流程到 Download/MASS导出/<流程名>/（含自定义节点模板 + 识别模板图片）。返回导出目录。 */
    fun exportFlow(context: Context, project: FlowProject): File? = runCatching {
        val exportRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "MASS导出",
        )
        exportRoot.mkdirs()
        val safe = project.name.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val dir = File(exportRoot, safe)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        // 1. project.json
        File(dir, "project.json").writeText(
            kotlinx.serialization.json.Json { prettyPrint = true }
                .encodeToString(FlowProject.serializer(), project)
        )

        // 2. 自定义节点模板
        val customDir = File(dir, "custom_nodes").apply { mkdirs() }
        val allCustom = CustomNodeStore.listAll(context)
        project.nodes.mapNotNull { it.customNodeId.takeIf { id -> id.isNotBlank() } }
            .distinct()
            .forEach { id ->
                allCustom.find { it.id == id }?.let { tpl ->
                    File(customDir, "${tpl.id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")}.json").writeText(
                        kotlinx.serialization.json.Json { prettyPrint = true }
                            .encodeToString(CustomNodeTemplate.serializer(), tpl)
                    )
                }
            }

        // 3. 识别模板图片
        val tplDir = File(dir, "templates").apply { mkdirs() }
        val allTemplates = TemplateStore.listAll(context)
        project.nodes.filter { it.kind == FlowNodeKind.IMAGE && it.templateId.isNotBlank() }
            .map { it.templateId }.distinct()
            .forEach { tid ->
                allTemplates.find { it.id == tid }?.let { t ->
                    runCatching { File(t.file).copyTo(File(tplDir, "${t.id}.png"), overwrite = true) }
                }
            }

        dir
    }.getOrNull()

    /** 导入流程文件夹（SAF 目录 uri）：加载流程 + 自定义节点 + 模板图片。返回提示消息。 */
    fun importFolder(context: Context, uri: Uri): String = runCatching {
        // 列出根目录条目
        val entries = listDirectory(context, uri)
        var flowName: String? = null

        // 1. project.json
        entries.firstOrNull { it.name == "project.json" }?.let { f ->
            val text = context.contentResolver.openInputStream(f.uri)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val fallback = uri.lastPathSegment?.substringAfterLast(':') ?: "导入流程"
            val project = parseImportJson(text, fallback)
            flowName = project.name.trim().ifBlank { fallback }
            val dir = File(context.filesDir, "workshop/${flowName}")
            dir.mkdirs()
            File(dir, "project.json").writeText(
                kotlinx.serialization.json.Json { prettyPrint = true }
                    .encodeToString(FlowProject.serializer(), project)
            )
        }

        // 2. custom_nodes/(json)
        var customCount = 0
        entries.firstOrNull { it.name == "custom_nodes" && it.isDirectory }?.let { sub ->
            listDirectory(context, sub.uri).forEach { f ->
                if (f.name.endsWith(".json")) {
                    val text = context.contentResolver.openInputStream(f.uri)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (CustomNodeStore.import(context, text)) customCount++
                }
            }
        }

        // 3. templates/(png) → 模板库根目录
        var tplCount = 0
        entries.firstOrNull { it.name == "templates" && it.isDirectory }?.let { sub ->
            listDirectory(context, sub.uri).forEach { f ->
                if (f.name.endsWith(".png")) {
                    runCatching {
                        val dst = File(TemplateStore.dir(context), f.name)
                        context.contentResolver.openInputStream(f.uri)?.use { input ->
                            FileOutputStream(dst).use { input.copyTo(it) }
                        }
                        tplCount++
                    }
                }
            }
        }

        val name = flowName ?: "未知流程"
        "已导入流程「$name」+ 自定义节点 $customCount 个 + 模板图片 $tplCount 张"
    }.getOrElse { e ->
        "导入失败：${e.message}"
    }

    /** 兼容：按 uri 读取 displayName（用于单文件导入默认名）。 */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    // ========== DocumentsContract 目录遍历（无外部依赖） ==========

    private data class DocEntry(val name: String, val uri: Uri, val isDirectory: Boolean)

    /** 列出 SAF 目录 uri 下的直接子项。 */
    private fun listDirectory(context: Context, treeUri: Uri): List<DocEntry> {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val result = mutableListOf<DocEntry>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                result += DocEntry(name, childUri, isDir)
            }
        }
        return result
    }
}