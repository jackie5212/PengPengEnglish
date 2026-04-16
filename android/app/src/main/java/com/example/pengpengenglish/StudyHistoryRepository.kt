package com.example.pengpengenglish

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class StudyHistoryRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Entry(
        val id: String,
        val packId: String,
        val packName: String,
        val pageIndex: Int,
        val totalPages: Int,
        val savedAtMs: Long
    ) {
        fun displayLine(): String =
            "$packName  第 ${pageIndex + 1}/$totalPages 页"
    }

    fun loadAll(): MutableList<Entry> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Entry(
                            id = o.getString("id"),
                            packId = o.getString("packId"),
                            packName = o.getString("packName"),
                            pageIndex = o.getInt("pageIndex"),
                            totalPages = o.getInt("totalPages"),
                            savedAtMs = o.getLong("savedAtMs")
                        )
                    )
                }
            }.toMutableList()
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    fun recordSession(packId: String, packName: String, pageIndex: Int, totalPages: Int) {
        val list = loadAll()
        list.removeAll { it.packId == packId }
        list.add(
            0,
            Entry(
                id = UUID.randomUUID().toString(),
                packId = packId,
                packName = packName,
                pageIndex = pageIndex.coerceAtLeast(0),
                totalPages = totalPages.coerceAtLeast(1),
                savedAtMs = System.currentTimeMillis()
            )
        )
        while (list.size > MAX_RECORDS) list.removeAt(list.lastIndex)
        saveAll(list)
    }

    fun deleteById(id: String) {
        val list = loadAll().filter { it.id != id }.toMutableList()
        saveAll(list)
    }

    /** 首次无记录时写入 5 条示例，便于查看界面效果 */
    fun ensureDefaultSamplesIfEmpty() {
        if (loadAll().isNotEmpty()) return
        val demos = listOf(
            Entry("demo-1", "demo1", "示例：初中词汇", 2, 120, System.currentTimeMillis() - 86400000L * 5),
            Entry("demo-2", "demo2", "示例：高中词汇", 0, 200, System.currentTimeMillis() - 86400000L * 4),
            Entry("demo-3", "demo3", "示例：四级词汇", 15, 350, System.currentTimeMillis() - 86400000L * 3),
            Entry("demo-4", "demo4", "示例：考研词汇", 7, 400, System.currentTimeMillis() - 86400000L * 2),
            Entry("demo-5", "demo5", "示例：小学大纲", 4, 56, System.currentTimeMillis() - 86400000L)
        )
        saveAll(demos.toMutableList())
    }

    private fun saveAll(list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("packId", e.packId)
                    put("packName", e.packName)
                    put("pageIndex", e.pageIndex)
                    put("totalPages", e.totalPages)
                    put("savedAtMs", e.savedAtMs)
                }
            )
        }
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "study_history"
        private const val KEY_RECORDS = "records_json"
        private const val MAX_RECORDS = 5
    }
}
