package com.example.pengpengenglish

class FliteNativeBridge {
    external fun nativeInit(): Boolean
    external fun nativeLastError(): String
    external fun nativeSpeak(text: String, localeTag: String, outWavPath: String): Boolean
    external fun nativeSetVoice(voiceId: String): Boolean
    external fun nativeRelease()

    companion object {
        init {
            System.loadLibrary("flite_jni")
        }
    }
}
