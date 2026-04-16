package com.example.pengpeng_english

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 自原版 `com.example.pengpengenglish.PiperOnnxTtsManager` 拷贝。
 * Flutter 工程由 Dart [PiperAssetBootstrap] 将 assets 解压到 `noBackupFilesDir/piper/amy_int8/`，
 * 故内置 Amy 的 [ModelOption.assetDir] 置为 null，不再从 Android assets 拷贝。
 */
class PiperOnnxTtsManager(private val context: Context) {
    private val engineCache = LinkedHashMap<String, OfflineTts>(8, 0.75f, true)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var currentModel: ModelOption = ModelOption.AMY_INT8

    fun availableModels(): List<ModelOption> {
        val builtIn = ModelOption.entries.toMutableList()
        val ids = builtIn.map { it.id }.toMutableSet()
        val root = File(context.noBackupFilesDir, "piper")
        val downloaded = mutableListOf<ModelOption>()
        if (root.exists()) {
            val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (dir in dirs) {
                if (dir.name in ids) continue
                val onnx = dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".onnx") } ?: continue
                val tokens = File(dir, "tokens.txt")
                val espeak = File(dir, "espeak-ng-data")
                val displayName = File(dir, "voice_name.txt")
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: dir.name
                val ready = tokens.exists() && espeak.exists() && espeakDirHasEnoughFiles(espeak)
                val supported = ready && isRuntimeCompatible(dir.name)
                downloaded += ModelOption(
                    id = dir.name,
                    label = when {
                        !ready -> "$displayName (downloaded, incomplete)"
                        !supported -> "$displayName (downloaded, unsupported)"
                        else -> displayName
                    },
                    assetDir = null,
                    modelFileName = onnx.name,
                    isSupported = supported
                )
            }
        }
        return builtIn + downloaded.sortedBy { it.label.lowercase() }
    }

    fun currentModel(): ModelOption = currentModel

    fun preloadModels(options: List<ModelOption>) {
        for (option in options) {
            if (!option.isSupported) continue
            try {
                getOrCreateEngine(option)
            } catch (_: Throwable) {
            }
        }
    }

    fun init(option: ModelOption, onReady: () -> Unit, onError: (String) -> Unit) {
        val start = SystemClock.elapsedRealtime()
        try {
            if (!option.isSupported) {
                onError("该模型暂不兼容当前引擎：${option.label}")
                return
            }
            getOrCreateEngine(option)
            currentModel = option
            Log.d(PERF_TAG, "piper init(${option.id}): cost=${SystemClock.elapsedRealtime() - start}ms")
            onReady()
        } catch (e: Throwable) {
            onError(e.message ?: "unknown")
        }
    }

    private fun prepareRuntimeAssets(model: ModelOption): File {
        val runtimeDir = File(context.noBackupFilesDir, "piper/${model.id}")
        if (!runtimeDir.exists()) runtimeDir.mkdirs()

        if (!model.assetDir.isNullOrBlank()) {
            copyAssetFileIfNeeded("${model.assetDir}/${model.modelFileName}", File(runtimeDir, model.modelFileName))
            copyAssetFileIfNeeded("${model.assetDir}/tokens.txt", File(runtimeDir, "tokens.txt"))
            copyAssetDirIfNeeded("${model.assetDir}/espeak-ng-data", File(runtimeDir, "espeak-ng-data"))
        }
        return runtimeDir
    }

    private fun copyAssetFileIfNeeded(assetPath: String, outFile: File) {
        if (outFile.exists() && outFile.length() > 0L) return
        outFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun copyAssetDirIfNeeded(assetDir: String, outDir: File) {
        if (outDir.exists() && outDir.list()?.isNotEmpty() == true) return
        if (!outDir.exists()) outDir.mkdirs()

        val children = context.assets.list(assetDir) ?: emptyArray()
        for (name in children) {
            val childAssetPath = "$assetDir/$name"
            val grandChildren = context.assets.list(childAssetPath) ?: emptyArray()
            if (grandChildren.isNotEmpty()) {
                copyAssetDirIfNeeded(childAssetPath, File(outDir, name))
            } else {
                copyAssetFileIfNeeded(childAssetPath, File(outDir, name))
            }
        }
    }

    private fun isRuntimeCompatible(modelId: String): Boolean {
        val lower = modelId.lowercase()
        return lower.contains("-int8") || lower.contains("-fp16")
    }

    fun speak(text: String, onError: (String) -> Unit = {}) {
        try {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                onError("文本为空")
                return
            }
            var engine = getEngineForCurrentModel()
            if (engine == null && currentModel.isSupported) {
                engine = getOrCreateEngine(currentModel)
            }
            val e = engine ?: run {
                onError("Piper 未就绪")
                return
            }
            val audio = e.generate(trimmed, 0, 1.0f)
            val wavFile = File(context.cacheDir, "piper-out.wav")
            audio.save(wavFile.absolutePath)
            if (!wavFile.exists() || wavFile.length() <= 44) {
                onError("Piper 未生成有效音频文件")
                return
            }

            val path = wavFile.absolutePath
            val latch = CountDownLatch(1)
            var playErr: String? = null
            mainHandler.post {
                try {
                    player?.stop()
                    player?.release()
                    player = MediaPlayer().apply {
                        setDataSource(path)
                        prepare()
                        start()
                    }
                } catch (ex: Throwable) {
                    playErr = ex.message ?: "MediaPlayer"
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(15, TimeUnit.SECONDS)) {
                onError("播放启动超时")
                return
            }
            playErr?.let { onError(it) }
        } catch (e: Throwable) {
            onError(e.message ?: "unknown")
        }
    }

    fun release() {
        try {
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    player?.stop()
                    player?.release()
                    player = null
                } finally {
                    latch.countDown()
                }
            }
            latch.await(3, TimeUnit.SECONDS)
            synchronized(this) {
                engineCache.values.forEach {
                    try {
                        it.release()
                    } catch (_: Throwable) {
                    }
                }
                engineCache.clear()
            }
        } catch (_: Throwable) {
        }
    }

    private fun getEngineForCurrentModel(): OfflineTts? {
        return synchronized(this) {
            engineCache[currentModel.id]
        }
    }

    @Synchronized
    private fun getOrCreateEngine(option: ModelOption): OfflineTts {
        engineCache[option.id]?.let {
            Log.d(PERF_TAG, "piper cache hit: ${option.id}")
            return it
        }
        val start = SystemClock.elapsedRealtime()

        val runtimeDir = prepareRuntimeAssets(option)
        val modelFile = File(runtimeDir, option.modelFileName)
        val tokensFile = File(runtimeDir, "tokens.txt")
        val espeakDir = File(runtimeDir, "espeak-ng-data")
        if (!modelFile.isFile || modelFile.length() < 4096L) {
            throw IllegalStateException("模型文件缺失或过小: ${option.id}")
        }
        if (!tokensFile.isFile || tokensFile.length() < 16L) {
            throw IllegalStateException("tokens.txt 无效: ${option.id}")
        }
        if (!espeakDirHasEnoughFiles(espeakDir)) {
            throw IllegalStateException("espeak-ng-data 无效或文件过少: ${option.id}")
        }
        val modelPath = modelFile.absolutePath
        val tokensPath = tokensFile.absolutePath
        val espeakDirPath = espeakDir.absolutePath

        val vits = OfflineTtsVitsModelConfig(
            modelPath,
            "",
            tokensPath,
            espeakDirPath,
            "",
            0.667f,
            0.8f,
            1.0f
        )
        val modelConfig = OfflineTtsModelConfig().apply {
            this.vits = vits
            numThreads = 1
            debug = false
            provider = "cpu"
        }
        val config = OfflineTtsConfig().apply {
            this.model = modelConfig
            maxNumSentences = 1
            silenceScale = 1.0f
        }
        val engine = OfflineTts(config = config)
        engineCache[option.id] = engine

        // 逐出时永不释放 currentModel；且提高上限，避免低端机上频繁 create/release 触发 JNI 不稳定。
        while (engineCache.size > MAX_CACHED_ENGINES) {
            val victimKey = engineCache.keys.firstOrNull { it != currentModel.id } ?: break
            val eldest = engineCache.remove(victimKey)
            try {
                eldest?.release()
            } catch (_: Throwable) {
            }
        }
        Log.d(PERF_TAG, "piper cache miss build(${option.id}): cost=${SystemClock.elapsedRealtime() - start}ms")
        return engine
    }

    data class ModelOption(
        val id: String,
        val label: String,
        val assetDir: String?,
        val modelFileName: String,
        val isSupported: Boolean = true
    ) {
        companion object {
            val AMY_INT8 = ModelOption(
                id = "amy_int8",
                label = "Amy (medium-int8)",
                assetDir = null,
                modelFileName = "en_US-amy-medium.onnx"
            )

            val entries: List<ModelOption> = listOf(
                AMY_INT8
            )
        }
    }

    /** 不能只看顶层 [File.list]，部分设备上子树在子目录内。 */
    private fun espeakDirHasEnoughFiles(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val n = dir.walkTopDown().asSequence().take(2048).count { it.isFile }
        return n >= 16
    }

    companion object {
        private const val MAX_CACHED_ENGINES = 8
        private const val PERF_TAG = "PPStartPerf"
    }
}
