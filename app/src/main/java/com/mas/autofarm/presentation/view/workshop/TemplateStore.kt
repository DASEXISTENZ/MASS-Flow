package com.mas.autofarm.presentation.view.workshop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 模板库管理：模板存 filesDir/workshop/templates/ 下，按文件夹树组织（可套娃）。
 * 文件夹 = 真实目录；模板元数据（分辨率等）存 templates.json。
 */
object TemplateStore {

    fun dir(context: Context): File =
        File(context.filesDir, "workshop/templates").apply { mkdirs() }

    /** 某路径（如 "主城/活动"）对应的目录；空=根 */
    fun folderDir(context: Context, path: String): File {
        val root = dir(context)
        if (path.isBlank()) return root
        return File(root, path.replace('/', File.separatorChar)).apply { mkdirs() }
    }

    /** 元数据（模板 id → 模板信息） */
    private fun metaFile(context: Context): File = File(dir(context), "templates.json")

    private fun loadMeta(context: Context): MutableMap<String, FlowTemplate> {
        val f = metaFile(context)
        if (!f.exists()) return mutableMapOf()
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString(
                MapSerializer(String.serializer(), FlowTemplate.serializer()), f.readText()
            ).toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun saveMeta(context: Context, meta: Map<String, FlowTemplate>) {
        runCatching {
            metaFile(context).writeText(
                kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
                    MapSerializer(String.serializer(), FlowTemplate.serializer()), meta
                )
            )
        }
    }

    /** 当前路径下的子文件夹名（套娃） */
    fun listFolders(context: Context, path: String): List<String> {
        val d = folderDir(context, path)
        return d.listFiles()?.filter { it.isDirectory && it.name != "templates.json" }
            ?.map { it.name }?.sorted() ?: emptyList()
    }

    /** 当前路径下的模板（不含子文件夹） */
    fun list(context: Context, path: String): List<FlowTemplate> {
        val meta = loadMeta(context)
        val d = folderDir(context, path)
        return d.listFiles()?.filter { it.isFile && it.extension == "png" }
            ?.map { f ->
                val size = runCatching {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, opts)
                    opts.outWidth to opts.outHeight
                }.getOrDefault(0 to 0)
                val id = f.name.removeSuffix(".png")
                val m = meta[id]
                FlowTemplate(
                    id = id,
                    name = m?.name ?: id,
                    file = f.absolutePath,
                    width = size.first,
                    height = size.second,
                    category = path,
                    recResolution = m?.recResolution ?: "",
                )
            }?.sortedBy { it.name } ?: emptyList()
    }

    /** 全部模板（全目录递归，供搜索/节点选择用） */
    fun listAll(context: Context): List<FlowTemplate> {
        val result = mutableListOf<FlowTemplate>()
        fun walk(dir: File, path: String) {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    walk(f, if (path.isEmpty()) f.name else "$path/${f.name}")
                } else if (f.extension == "png") {
                    val id = f.name.removeSuffix(".png")
                    result += FlowTemplate(
                        id = id,
                        name = id,
                        file = f.absolutePath,
                        width = runCatching {
                            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(f.absolutePath, o)
                            o.outWidth
                        }.getOrDefault(0),
                        height = runCatching {
                            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(f.absolutePath, o)
                            o.outHeight
                        }.getOrDefault(0),
                        category = path,
                    )
                }
            }
        }
        walk(dir(context), "")
        return result.sortedBy { it.name }
    }

    /** 新建文件夹 */
    fun createFolder(context: Context, path: String, name: String): Boolean {
        val safe = sanitize(name)
        if (safe.isBlank()) return false
        val d = File(folderDir(context, path), safe)
        if (d.exists()) return true // 已存在视为成功
        return d.mkdirs()
    }

    /** 重命名文件夹（含其下所有内容移动） */
    fun renameFolder(context: Context, path: String, oldName: String, newName: String): Boolean {
        val safe = sanitize(newName)
        if (safe.isBlank() || safe == oldName) return false
        val parent = folderDir(context, path)
        val old = File(parent, oldName)
        val dst = File(parent, safe)
        if (!old.isDirectory || dst.exists()) return false
        // 更新元数据中的分类路径
        val meta = loadMeta(context)
        var changed = false
        meta.forEach { (id, tpl) ->
            if (tpl.category == path + "/" + oldName || tpl.category.startsWith(path + "/" + oldName + "/")) {
                meta[id] = tpl.copy(category = tpl.category.replaceFirst(oldName, safe))
                changed = true
            }
        }
        if (changed) saveMeta(context, meta)
        return old.renameTo(dst)
    }

    /** 删除文件夹（递归） */
    fun deleteFolder(context: Context, path: String, name: String) {
        val folder = File(folderDir(context, path), name)
        if (folder.isDirectory) {
            // 清理元数据
            val meta = loadMeta(context)
            val prefix = path + "/" + name
            val toRemove = meta.filterValues { it.category == prefix || it.category.startsWith(prefix + "/") }.keys
            toRemove.forEach { meta.remove(it) }
            if (toRemove.isNotEmpty()) saveMeta(context, meta)
            folder.deleteRecursively()
        }
    }

    /** 保存模板到指定路径（自动去白边 + 压缩） */
    fun save(
        context: Context, src: Uri, name: String, roi: IntArray?,
        path: String = "", recResolution: String = "",
    ): FlowTemplate? {
        return runCatching {
            val srcBmp = context.contentResolver.openInputStream(src)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null
            var bmp = if (roi != null && roi.size == 4) {
                val x = roi[0].coerceIn(0, srcBmp.width - 1)
                val y = roi[1].coerceIn(0, srcBmp.height - 1)
                val w = roi[2].coerceIn(1, srcBmp.width - x)
                val h = roi[3].coerceIn(1, srcBmp.height - y)
                Bitmap.createBitmap(srcBmp, x, y, w, h)
            } else srcBmp
            bmp = autoTrimBlank(bmp)
            bmp = scaleDown(bmp, maxEdge = 512)
            val safeName = sanitize(name).ifEmpty { "tpl${System.currentTimeMillis() % 10000}" }
            val file = File(folderDir(context, path), "$safeName.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val tpl = FlowTemplate(
                id = safeName,
                name = safeName,
                file = file.absolutePath,
                width = bmp.width,
                height = bmp.height,
                category = path,
                recResolution = recResolution,
            )
            val meta = loadMeta(context)
            meta[safeName] = tpl
            saveMeta(context, meta)
            tpl
        }.getOrNull()
    }

    /** 保存位图模板（保留透明通道，供像素抠图使用）。 */
    fun saveBitmap(
        context: Context, bmp: android.graphics.Bitmap, name: String,
        path: String = "", recResolution: String = "",
    ): FlowTemplate? = runCatching {
        val safeName = sanitize(name).ifEmpty { "tpl${System.currentTimeMillis() % 10000}" }
        val file = File(folderDir(context, path), "$safeName.png")
        FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val tpl = FlowTemplate(
            id = safeName, name = safeName, file = file.absolutePath,
            width = bmp.width, height = bmp.height, category = path, recResolution = recResolution,
        )
        val meta = loadMeta(context)
        meta[safeName] = tpl
        saveMeta(context, meta)
        tpl
    }.getOrNull()

    /** 重命名模板（改文件名 + 元数据） */
    fun renameTemplate(context: Context, tpl: FlowTemplate, newName: String): Boolean {
        val safe = sanitize(newName)
        if (safe.isBlank() || safe == tpl.name) return false
        val src = File(tpl.file)
        if (!src.exists()) return false
        val dst = File(src.parentFile, "$safe.png")
        if (dst.exists()) return false
        val ok = src.renameTo(dst)
        if (ok) {
            val meta = loadMeta(context)
            meta.remove(tpl.id)
            meta[safe] = tpl.copy(id = safe, name = safe, file = dst.absolutePath)
            saveMeta(context, meta)
        }
        return ok
    }

    /** 更新模板元数据（分辨率等） */
    fun updateMeta(context: Context, tpl: FlowTemplate) {
        val meta = loadMeta(context)
        meta[tpl.id] = tpl
        saveMeta(context, meta)
    }

    /** 移动模板到另一文件夹 */
    fun moveTemplate(context: Context, tpl: FlowTemplate, destPath: String): Boolean {
        val src = File(tpl.file)
        if (!src.exists()) return false
        val dst = File(folderDir(context, destPath), src.name)
        if (dst.exists()) return false
        val ok = src.renameTo(dst)
        if (ok) {
            val meta = loadMeta(context)
            meta[tpl.id] = tpl.copy(file = dst.absolutePath, category = destPath)
            saveMeta(context, meta)
        }
        return ok
    }

    fun delete(context: Context, name: String) {
        val meta = loadMeta(context)
        val tpl = meta[name]
        val f = if (tpl != null) File(tpl.file) else File(dir(context), "$name.png")
        f.delete()
        meta.remove(name)
        saveMeta(context, meta)
    }

    private fun sanitize(s: String): String =
        s.trim().replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")

    /** 自动裁掉四周与边角色一致的空白区域 */
    private fun autoTrimBlank(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w < 4 || h < 4) return bmp
        val corner = bmp.getPixel(0, 0)
        fun isBlank(x: Int, y: Int): Boolean {
            val c = bmp.getPixel(x, y)
            return kotlin.math.abs(android.graphics.Color.red(c) - android.graphics.Color.red(corner)) < 25 &&
                kotlin.math.abs(android.graphics.Color.green(c) - android.graphics.Color.green(corner)) < 25 &&
                kotlin.math.abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(corner)) < 25
        }
        var left = 0
        var right = w - 1
        var top = 0
        var bottom = h - 1
        outer@ for (x in 0 until w) {
            for (y in 0 until h) {
                if (!isBlank(x, y)) { left = x; break@outer }
            }
        }
        outer@ for (x in (w - 1) downTo 0) {
            for (y in 0 until h) {
                if (!isBlank(x, y)) { right = x; break@outer }
            }
        }
        outer@ for (y in 0 until h) {
            for (x in 0 until w) {
                if (!isBlank(x, y)) { top = y; break@outer }
            }
        }
        outer@ for (y in (h - 1) downTo 0) {
            for (x in 0 until w) {
                if (!isBlank(x, y)) { bottom = y; break@outer }
            }
        }
        if (right <= left || bottom <= top) return bmp
        return Bitmap.createBitmap(bmp, left, top, right - left + 1, bottom - top + 1)
    }

    /** 最长边限制缩放（省空间） */
    private fun scaleDown(bmp: Bitmap, maxEdge: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val max = maxOf(w, h)
        if (max <= maxEdge) return bmp
        val scale = maxEdge.toFloat() / max
        return Bitmap.createScaledBitmap(
            bmp, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true
        )
    }
}