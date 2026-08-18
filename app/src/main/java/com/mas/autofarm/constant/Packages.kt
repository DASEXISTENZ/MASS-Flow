package com.mas.autofarm.constant
import android.content.Context

object Packages : Iterable<Map.Entry<String, String>> {
    private data class ClientInfo(val packageName: String, val displayName: String)
    @Volatile
    private var clients: Map<String, ClientInfo> = emptyMap()
    @Volatile
    var defaultTaskEntry: String = ""
        private set

    /** 从 assets/game.json 加载当前适配的游戏客户端（通用壳，游戏通过配置套用）。 */
    fun init(context: Context) {
        try {
            val json = context.assets.open("game.json").bufferedReader().use { it.readText() }
            val obj = org.json.JSONObject(json)
            val type = obj.optString("clientType")
            if (type.isNotBlank()) {
                clients = mapOf(
                    type to ClientInfo(
                        packageName = obj.getString("package"),
                        displayName = obj.optString("name", type),
                    )
                )
            }
            defaultTaskEntry = obj.optString("defaultTaskEntry", "")
        } catch (_: Exception) {
            clients = emptyMap()
            defaultTaskEntry = ""
        }
    }

    operator fun get(type: String): String? = clients[type]?.packageName
    fun displayNameOf(type: String): String? = clients[type]?.displayName
    val clientTypes: List<String> get() = clients.keys.toList()
    override fun iterator(): Iterator<Map.Entry<String, String>> {
        val entries: List<Map.Entry<String, String>> = clients.entries.map {
            java.util.AbstractMap.SimpleEntry(it.key, it.value.packageName)
        }
        return entries.iterator()
    }
}
