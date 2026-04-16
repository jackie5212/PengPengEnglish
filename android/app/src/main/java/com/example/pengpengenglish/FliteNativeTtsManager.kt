package com.example.pengpengenglish

import android.content.Context
import android.media.MediaPlayer
import java.io.File

class FliteNativeTtsManager(private val context: Context) {
    private val bridge = FliteNativeBridge()
    private var ready = false
    private var selectedVoice: VoiceOption = VoiceOption.KAL
    private var player: MediaPlayer? = null

    fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        ready = bridge.nativeInit()
        if (ready) {
            bridge.nativeSetVoice(selectedVoice.id)
            onReady()
        } else {
            onError(bridge.nativeLastError())
        }
    }

    fun availableVoices(): List<VoiceOption> = VoiceOption.entries

    fun currentVoice(): VoiceOption = selectedVoice

    fun setVoice(voice: VoiceOption, onError: (String) -> Unit = {}) {
        selectedVoice = voice
        if (!ready) return
        val ok = bridge.nativeSetVoice(voice.id)
        if (!ok) onError(bridge.nativeLastError())
    }

    fun speak(text: String, onError: (String) -> Unit = {}) {
        if (!ready) {
            onError("Flite 未就绪：${bridge.nativeLastError()}")
            return
        }
        val wavFile = File(context.cacheDir, "flite-out.wav")
        val ok = bridge.nativeSpeak(text.trim(), "en-US", wavFile.absolutePath)
        if (!ok) onError(bridge.nativeLastError())
        if (!wavFile.exists() || wavFile.length() <= 44) {
            onError("Flite 未生成有效音频文件")
            return
        }
        try {
            player?.stop()
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(wavFile.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            onError("Flite 播放器失败：${e.message ?: "unknown"}")
        }
    }

    fun release() {
        player?.stop()
        player?.release()
        player = null
        bridge.nativeRelease()
        ready = false
    }

    enum class VoiceOption(val id: String, val label: String) {
        KAL("kal", "Kal (经典男声)"),
        KAL16("kal16", "Kal16 (16kHz 男声)"),
        AWB("awb", "AWB (苏格兰男声)"),
        RMS("rms", "RMS (美式男声)"),
        SLT("slt", "SLT (美式女声)")
    }
}
