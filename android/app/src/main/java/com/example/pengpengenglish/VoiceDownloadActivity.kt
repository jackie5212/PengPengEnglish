package com.example.pengpengenglish

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class VoiceDownloadActivity : ComponentActivity() {
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var buttonContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var percentView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_download)

        statusView = findViewById(R.id.tvDownloadStatus)
        buttonContainer = findViewById(R.id.modelButtonContainer)
        progressBar = findViewById(R.id.progressDownload)
        percentView = findViewById(R.id.tvDownloadPercent)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        loadModelIndex()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    private fun loadModelIndex() {
        statusView.text = "正在读取模型列表..."
        ioExecutor.execute {
            try {
                val list = parseModelIndex(downloadText(MODEL_INDEX_URL))
                runOnUiThread {
                    renderButtons(list)
                    statusView.text = if (list.isEmpty()) "模型列表为空" else "可下载模型：${list.size} 个"
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    statusView.text = "读取模型列表失败：${e.message ?: "unknown"}"
                }
            }
        }
    }

    private fun renderButtons(models: List<RemoteModel>) {
        buttonContainer.removeAllViews()
        for (model in models) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val downloadBtn = Button(this).apply {
                text = "下载 ${model.displayName}"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { downloadAndInstall(model) }
            }
            row.addView(downloadBtn)

            if (isInstalled(model.fileName)) {
                val deleteBtn = Button(this).apply {
                    text = "删除"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setOnClickListener { deleteModel(model.fileName) }
                }
                row.addView(deleteBtn)
            }
            buttonContainer.addView(row)
        }
    }

    private fun downloadAndInstall(model: RemoteModel) {
        val fileName = model.fileName
        statusView.text = "正在下载：$fileName"
        updateProgress(0)
        ioExecutor.execute {
            try {
                val downloadDir = File(cacheDir, "voice-downloads")
                if (!downloadDir.exists()) downloadDir.mkdirs()

                val archiveFile = File(downloadDir, fileName)
                val url = "$MODEL_BASE_URL/$fileName"
                downloadFile(url, archiveFile) { percent ->
                    runOnUiThread { updateProgress(percent) }
                }
                runOnUiThread {
                    statusView.text = "下载完成，正在解压：$fileName"
                }

                val runtimeRoot = File(noBackupFilesDir, "piper/${modelIdFromArchive(fileName)}")
                if (!runtimeRoot.exists()) runtimeRoot.mkdirs()
                clearDirectory(runtimeRoot)
                val unzipStartMs = System.currentTimeMillis()
                runOnUiThread {
                    setProgressIndeterminate(false)
                    updateProgress(0)
                }
                extractTarBz2ToRuntime(archiveFile, runtimeRoot) { processed, percent ->
                    runOnUiThread {
                        val etaText = estimateEtaTextByPercent(percent, unzipStartMs)
                        statusView.text = "正在解压：$fileName（已处理 $processed 项，约 $percent%，预计剩余 $etaText）"
                        updateProgress(percent)
                    }
                }
                runOnUiThread {
                    statusView.text = "解压完成，正在校验：$fileName"
                }

                // Save display name so model list can show narrator only.
                File(runtimeRoot, "voice_name.txt").writeText(model.displayName)

                val onnxCount = runtimeRoot.listFiles()?.count { it.isFile && it.name.endsWith(".onnx") } ?: 0
                val tokenOk = File(runtimeRoot, "tokens.txt").exists()
                val espeakOk = File(runtimeRoot, "espeak-ng-data").exists()
                val valid = onnxCount > 0 && tokenOk && espeakOk

                runOnUiThread {
                    updateProgress(100)
                    statusView.text = if (valid) {
                        "已完成：$fileName"
                    } else {
                        "未完成：$fileName (onnx=$onnxCount, tokens=$tokenOk, espeak=$espeakOk)"
                    }
                    loadModelIndex()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    statusView.text = "下载失败：${e.message ?: "unknown"}"
                }
            }
        }
    }

    private fun deleteModel(fileName: String) {
        statusView.text = "正在删除：$fileName"
        ioExecutor.execute {
            try {
                val modelId = modelIdFromArchive(fileName)
                val runtimeRoot = File(noBackupFilesDir, "piper/$modelId")
                if (runtimeRoot.exists()) {
                    clearDirectory(runtimeRoot)
                    runtimeRoot.delete()
                }

                val archiveFile = File(File(cacheDir, "voice-downloads"), fileName)
                if (archiveFile.exists()) {
                    archiveFile.delete()
                }

                runOnUiThread {
                    statusView.text = "已删除：$fileName"
                    updateProgress(0)
                    loadModelIndex()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    statusView.text = "删除失败：${e.message ?: "unknown"}"
                }
            }
        }
    }

    private fun isInstalled(fileName: String): Boolean {
        val modelId = modelIdFromArchive(fileName)
        val runtimeRoot = File(noBackupFilesDir, "piper/$modelId")
        if (!runtimeRoot.exists()) return false
        val onnxOk = runtimeRoot.listFiles()?.any { it.isFile && it.name.endsWith(".onnx") } == true
        val tokenOk = File(runtimeRoot, "tokens.txt").exists()
        val espeakOk = File(runtimeRoot, "espeak-ng-data").exists()
        return onnxOk || tokenOk || espeakOk
    }

    private fun modelIdFromArchive(fileName: String): String {
        return fileName.removeSuffix(".tar.bz2")
    }

    private fun parseModelIndex(raw: String): List<RemoteModel> {
        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()
        val out = mutableListOf<RemoteModel>()
        var pendingName: String? = null
        for (line in lines) {
            val isBracketName = line.startsWith("[") && line.endsWith("]") && line.length > 2
            if (isBracketName) {
                pendingName = line.substring(1, line.length - 1).trim()
                continue
            }
            if (!line.endsWith(".tar.bz2")) continue
            val display = pendingName?.takeIf { it.isNotBlank() } ?: line.removeSuffix(".tar.bz2")
            out += RemoteModel(displayName = display, fileName = line)
            pendingName = null
        }
        return out
    }

    private fun downloadText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${conn.responseCode}")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return text
    }

    private fun downloadFile(url: String, outFile: File, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${conn.responseCode}")
        }
        val totalBytes = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read: Int
                var downloaded = 0L
                var lastPercent = -1
                while (input.read(buffer).also { read = it } >= 0) {
                    output.write(buffer, 0, read)
                    if (totalBytes > 0) {
                        downloaded += read.toLong()
                        val percent = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        }
        if (totalBytes <= 0L) {
            onProgress(100)
        }
        conn.disconnect()
    }

    private fun updateProgress(percent: Int) {
        progressBar.progress = percent
        percentView.text = "$percent%"
    }

    private fun setProgressIndeterminate(indeterminate: Boolean) {
        progressBar.isIndeterminate = indeterminate
        if (indeterminate) {
            percentView.text = "解压中..."
        }
    }

    private fun estimateEtaTextByPercent(percent: Int, startMs: Long): String {
        if (percent <= 0 || percent >= 100) return "0 秒"
        val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        val totalMs = (elapsedMs * 100L) / percent
        val remainSec = ((totalMs - elapsedMs) / 1000L).toInt().coerceAtLeast(1)
        return if (remainSec < 60) {
            "$remainSec 秒"
        } else {
            "${remainSec / 60} 分 ${remainSec % 60} 秒"
        }
    }

    private fun extractTarBz2ToRuntime(
        archive: File,
        targetDir: File,
        onProgress: (processedEntries: Int, percent: Int) -> Unit
    ) {
        FileInputStream(archive).use { fis ->
            val countingIn = CountingInputStream(fis)
            val totalBytes = archive.length().coerceAtLeast(1L)
            BZip2CompressorInputStream(countingIn).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var processed = 0
                    var lastReportedMs = 0L
                    var entry: TarArchiveEntry? = tarIn.nextTarEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name.replace('\\', '/')
                            if (name.endsWith(".onnx")) {
                                writeEntry(tarIn, File(targetDir, name.substringAfterLast('/'))) {
                                    val percent = ((countingIn.bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                    val now = System.currentTimeMillis()
                                    if (now - lastReportedMs >= 200L || percent >= 100) {
                                        lastReportedMs = now
                                        onProgress(processed, percent)
                                    }
                                }
                            } else if (name.endsWith("tokens.txt")) {
                                writeEntry(tarIn, File(targetDir, "tokens.txt")) {
                                    val percent = ((countingIn.bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                    val now = System.currentTimeMillis()
                                    if (now - lastReportedMs >= 200L || percent >= 100) {
                                        lastReportedMs = now
                                        onProgress(processed, percent)
                                    }
                                }
                            } else if (name.contains("/espeak-ng-data/")) {
                                val rel = name.substringAfter("/espeak-ng-data/")
                                if (rel.isNotBlank()) {
                                    writeEntry(tarIn, File(targetDir, "espeak-ng-data/$rel")) {
                                        val percent = ((countingIn.bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                        val now = System.currentTimeMillis()
                                        if (now - lastReportedMs >= 200L || percent >= 100) {
                                            lastReportedMs = now
                                            onProgress(processed, percent)
                                        }
                                    }
                                }
                            }
                            processed++
                            val percent = ((countingIn.bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            val now = System.currentTimeMillis()
                            if (now - lastReportedMs >= 200L || percent >= 100) {
                                lastReportedMs = now
                                onProgress(processed, percent)
                            }
                        }
                        entry = tarIn.nextTarEntry
                    }
                    onProgress(processed, 100)
                }
            }
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) bytesRead += n.toLong()
            return n
        }
    }

    private fun writeEntry(
        input: TarArchiveInputStream,
        outFile: File,
        onChunkWritten: () -> Unit = {}
    ) {
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                output.write(buffer, 0, n)
                onChunkWritten()
            }
        }
    }

    private fun clearDirectory(dir: File) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) clearDirectory(f)
            f.delete()
        }
    }

    companion object {
        private const val MODEL_INDEX_URL = "http://47.97.36.224/tts/model.txt"
        private const val MODEL_BASE_URL = "http://47.97.36.224/tts"
    }

    private data class RemoteModel(
        val displayName: String,
        val fileName: String
    )
}
