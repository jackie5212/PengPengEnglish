package com.example.pengpengenglish

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

class SystemTtsManager(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var accent: Accent = Accent.AMERICAN
    private var usingGoogleEngine = false

    fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        release()

        val hasGoogleTts = isPackageInstalled(context.packageManager, GOOGLE_TTS_PACKAGE)
        usingGoogleEngine = hasGoogleTts

        val listener = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                onError("系统TTS初始化失败（status=$status）")
                return@OnInitListener
            }
            val engine = tts ?: run {
                onError("系统TTS初始化失败（tts=null）")
                return@OnInitListener
            }
            val setResult = engine.setLanguage(accent.locale)
            if (setResult == TextToSpeech.LANG_MISSING_DATA || setResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                onError("系统TTS不支持语言：${accent.locale}")
                return@OnInitListener
            }
            engine.setSpeechRate(DEFAULT_SPEECH_RATE)
            engine.setPitch(DEFAULT_PITCH)
            applyPreferredVoice(engine)
            ready = true
            onReady()
        }

        tts = if (hasGoogleTts) {
            TextToSpeech(context, listener, GOOGLE_TTS_PACKAGE)
        } else {
            TextToSpeech(context, listener)
        }
    }

    fun engineLabel(): String = if (usingGoogleEngine) "Google TTS" else "系统默认TTS"

    fun setAccent(value: Accent) {
        accent = value
        val engine = tts ?: return
        engine.setLanguage(value.locale)
        applyPreferredVoice(engine)
    }

    fun speak(text: String, onError: (String) -> Unit = {}) {
        val engine = tts
        if (!ready || engine == null) {
            onError("系统TTS未就绪")
            return
        }
        val result = engine.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null, "word-${System.currentTimeMillis()}")
        if (result != TextToSpeech.SUCCESS) {
            onError("系统TTS播放失败（code=$result）")
        }
    }

    fun release() {
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    enum class Accent(val label: String, val locale: Locale) {
        AMERICAN("美语", Locale.US),
        BRITISH("英音", Locale.UK)
    }

    companion object {
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        private const val DEFAULT_SPEECH_RATE = 0.9f
        private const val DEFAULT_PITCH = 1.05f

        private fun isPackageInstalled(packageManager: PackageManager, packageName: String): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun applyPreferredVoice(engine: TextToSpeech) {
        val voices = engine.voices ?: return
        if (voices.isEmpty()) return
        val candidates = voices
            .asSequence()
            .filter { it.locale.language.equals("en", ignoreCase = true) }
            .filter { it.locale.country.equals(accent.locale.country, ignoreCase = true) || it.locale.country.isBlank() }
            .filterNot { it.name.contains("network", ignoreCase = true) }
            .sortedByDescending(::voiceScore)
            .toList()
        val picked = candidates.firstOrNull() ?: return
        engine.voice = picked
    }

    private fun voiceScore(voice: Voice): Int {
        var score = 0
        val name = voice.name.lowercase(Locale.ROOT)
        if (voice.quality >= Voice.QUALITY_HIGH) score += 40
        if (voice.latency <= Voice.LATENCY_NORMAL) score += 20
        if (name.contains("wavenet") || name.contains("neural") || name.contains("studio")) score += 30
        if (name.contains("journey")) score += 25
        if (name.contains("standard")) score += 5
        if (voice.isNetworkConnectionRequired) score -= 10
        return score
    }
}
