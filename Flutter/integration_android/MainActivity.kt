package com.example.pengpeng_english

import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** 与 `android/app/.../MainActivity.kt` 同步：Piper 全部走单线程 Executor，避免 JNI 跨线程崩溃。 */
class MainActivity : FlutterActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val piperExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "piper-tts").apply { isDaemon = true }
    }
    private var piper: PiperOnnxTtsManager? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        piper = PiperOnnxTtsManager(this)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.pengpengenglish/paths")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "noBackupFilesDir" -> result.success(noBackupFilesDir.absolutePath)
                    "materializeAmyVoice" -> {
                        thread(name = "amy-assets") {
                            try {
                                AmyAssetMaterializer.materialize(this@MainActivity)
                                mainHandler.post { result.success(null) }
                            } catch (e: Throwable) {
                                mainHandler.post {
                                    result.error("amy_assets", e.message ?: e.javaClass.simpleName, null)
                                }
                            }
                        }
                    }
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.pengpengenglish/piper_tts")
            .setMethodCallHandler { call, result ->
                val mgr = piper ?: run {
                    result.error("no_piper", null, null)
                    return@setMethodCallHandler
                }
                when (call.method) {
                    "availableModels" -> {
                        piperExecutor.execute {
                            try {
                                val list = mgr.availableModels().map { m ->
                                    mapOf(
                                        "id" to m.id,
                                        "label" to m.label,
                                        "isSupported" to m.isSupported
                                    )
                                }
                                mainHandler.post { result.success(list) }
                            } catch (e: Throwable) {
                                mainHandler.post {
                                    result.error("piper", e.message ?: "availableModels", null)
                                }
                            }
                        }
                    }

                    "init" -> {
                        val id = call.argument<String>("modelId")
                        if (id.isNullOrBlank()) {
                            result.error("bad_args", "modelId", null)
                            return@setMethodCallHandler
                        }
                        piperExecutor.execute {
                            val opt = mgr.availableModels().firstOrNull { it.id == id }
                            if (opt == null) {
                                mainHandler.post { result.error("unknown_model", id, null) }
                                return@execute
                            }
                            mgr.init(
                                opt,
                                onReady = { mainHandler.post { result.success(null) } },
                                onError = { msg ->
                                    mainHandler.post { result.error("piper", msg, null) }
                                }
                            )
                        }
                    }

                    "speak" -> {
                        val text = call.argument<String>("text")
                        if (text.isNullOrBlank()) {
                            result.error("bad_args", "text", null)
                            return@setMethodCallHandler
                        }
                        piperExecutor.execute {
                            var err: String? = null
                            try {
                                mgr.speak(text) { err = it }
                            } catch (e: Throwable) {
                                err = e.message ?: "speak"
                            }
                            mainHandler.post {
                                if (err != null) {
                                    result.error("speak", err, null)
                                } else {
                                    result.success(null)
                                }
                            }
                        }
                    }

                    "release" -> {
                        piperExecutor.execute {
                            try {
                                mgr.release()
                            } catch (_: Throwable) {
                            }
                            mainHandler.post { result.success(null) }
                        }
                    }

                    "preload" -> {
                        val ids = call.argument<List<Any>>("modelIds")?.mapNotNull { it as? String } ?: emptyList()
                        piperExecutor.execute {
                            try {
                                val all = mgr.availableModels().filter { it.id in ids }
                                mgr.preloadModels(all)
                            } catch (_: Throwable) {
                            }
                            mainHandler.post { result.success(null) }
                        }
                    }

                    else -> result.notImplemented()
                }
            }
    }

    override fun onDestroy() {
        val p = piper
        piper = null
        if (p != null) {
            try {
                piperExecutor.execute {
                    try {
                        p.release()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
        piperExecutor.shutdown()
        super.onDestroy()
    }
}
