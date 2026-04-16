package com.example.pengpeng_english

import android.content.Context
import android.content.res.AssetManager
import io.flutter.FlutterInjector
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 将 Flutter 打包在 APK 的 `flutter_assets/assets/models/amy/` 整树拷贝到
 * [Context.getNoBackupFilesDir]/piper/amy_int8/。
 *
 * 大体积 `en_US-amy-medium.onnx` 若用 Dart [rootBundle.load] 整文件进堆，在部分机型上会失败或写入不完整；
 * 此处用 [AssetManager.open] 流式拷贝，与 [PiperOnnxTtsManager] 读取路径一致。
 *
 * 注意：少数 ROM 上 [AssetManager.list] 对深层目录可能返回 null/空，旧逻辑会误建「空目录」导致 espeak-ng-data 为空；
 * 现已改为：list 为空时只尝试按「单文件」打开，失败则抛错（由 Dart 侧回退 manifest 解压）。
 */
object AmyAssetMaterializer {

    private const val MIN_ONNX_BYTES = 65536L
    private const val MIN_TOKENS_BYTES = 16L
    /** espeak 树内至少应有若干文件，仅看顶层 [File.list] 不可靠。 */
    private const val MIN_ESPEAK_FILES = 32

    /** 与 Dart [PiperAssetBootstrap] 一致；升高后旧运行时目录会被删掉重拷。 */
    private const val AMY_BOOTSTRAP_VERSION = "5"

    fun materialize(context: Context) {
        val loader = FlutterInjector.instance().flutterLoader()
        check(loader.initialized()) { "Flutter 尚未初始化完成，请稍后再试" }

        val tokenKey = loader.getLookupKeyForAsset("assets/models/amy/tokens.txt")
        val flutterAssetRoot = tokenKey.substring(0, tokenKey.lastIndexOf('/'))

        val am = context.assets
        val assetRoot = resolveAssetRoot(am, flutterAssetRoot)
        check(assetRoot != null) {
            "APK 内未找到 Amy 资源目录（已尝试 models/amy 与 $flutterAssetRoot）。请确认资源已打包。"
        }

        val dest = File(context.noBackupFilesDir, "piper/amy_int8")
        if (dest.exists()) {
            dest.deleteRecursively()
        }
        check(dest.mkdirs()) { "无法创建 ${dest.path}" }

        copyAssetTree(am, assetRoot, dest)

        val onnx = File(dest, "en_US-amy-medium.onnx")
        check(onnx.isFile && onnx.length() >= MIN_ONNX_BYTES) {
            "en_US-amy-medium.onnx 无效：${onnx.length()} 字节（需 ≥ $MIN_ONNX_BYTES）。请全量重编安装，勿使用残缺 APK。"
        }
        val tok = File(dest, "tokens.txt")
        check(tok.isFile && tok.length() >= MIN_TOKENS_BYTES) { "tokens.txt 无效" }
        val esp = File(dest, "espeak-ng-data")
        val n = countFilesRecursive(esp)
        check(n >= MIN_ESPEAK_FILES) {
            "espeak-ng-data 内文件过少（$n，需 ≥ $MIN_ESPEAK_FILES），原生拷贝可能不完整"
        }
        File(dest, ".pp_amy_bootstrap").writeText(AMY_BOOTSTRAP_VERSION)
    }

    private fun countFilesRecursive(dir: File): Int {
        if (!dir.isDirectory) return 0
        return dir.walkTopDown().count { it.isFile }
    }

    private fun copyAssetTree(am: AssetManager, assetPath: String, destPath: File) {
        var children = am.list(assetPath)
        if (children == null) {
            children = emptyArray()
        }
        if (children.isEmpty()) {
            try {
                am.open(assetPath).use { input ->
                    destPath.parentFile?.mkdirs()
                    destPath.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: FileNotFoundException) {
                throw IOException("无法作为文件打开（且目录列举为空）: $assetPath", e)
            }
            return
        }
        if (!destPath.mkdirs() && !destPath.isDirectory) {
            throw IOException("无法创建目录: ${destPath.path}")
        }
        for (name in children) {
            if (name.isEmpty()) continue
            copyAssetTree(am, "$assetPath/$name", File(destPath, name))
        }
    }

    private fun resolveAssetRoot(am: AssetManager, flutterAssetRoot: String): String? {
        val candidates = listOf(
            "models/amy", // Android 原生 assets（更稳定，避免 Flutter 资产裁剪）
            flutterAssetRoot
        )
        for (root in candidates) {
            val children = am.list(root)
            if (!children.isNullOrEmpty()) {
                val hasTokens = children.contains("tokens.txt")
                val hasOnnx = children.contains("en_US-amy-medium.onnx")
                if (hasTokens && hasOnnx) return root
            }
        }
        return null
    }
}
