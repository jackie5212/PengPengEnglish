package com.example.pengpengenglish

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class StudyRecordActivity : ComponentActivity() {
    private lateinit var listContainer: LinearLayout
    private val repo by lazy { StudyHistoryRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study_record)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        listContainer = findViewById(R.id.recordListContainer)
        repo.ensureDefaultSamplesIfEmpty()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val entries = repo.loadAll()
        if (entries.isEmpty()) {
            val tv = TextView(this).apply {
                text = "暂无学习记录"
                textSize = 16f
                setPadding(0, 8, 0, 8)
            }
            listContainer.addView(tv)
            return
        }
        for (entry in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
            }
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = entry.displayLine()
                textSize = 15f
            }
            val del = Button(this).apply {
                text = "删除"
                setOnClickListener {
                    repo.deleteById(entry.id)
                    refreshList()
                }
            }
            row.addView(label)
            row.addView(del)
            listContainer.addView(row)
        }
    }
}
