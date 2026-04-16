#include <jni.h>
#include <string>
#include "flite.h"

static std::string g_last_error = "Flite not initialized.";
static cst_voice *g_voice = nullptr;
static std::string g_voice_id = "kal";

extern "C" cst_voice *register_cmu_us_kal(const char *voxdir);
extern "C" cst_voice *register_cmu_us_kal16(const char *voxdir);
extern "C" cst_voice *register_cmu_us_awb(const char *voxdir);
extern "C" cst_voice *register_cmu_us_rms(const char *voxdir);
extern "C" cst_voice *register_cmu_us_slt(const char *voxdir);

static cst_voice *select_voice(const std::string &voice_id) {
    if (voice_id == "kal") return register_cmu_us_kal(nullptr);
    if (voice_id == "kal16") return register_cmu_us_kal16(nullptr);
    if (voice_id == "awb") return register_cmu_us_awb(nullptr);
    if (voice_id == "rms") return register_cmu_us_rms(nullptr);
    if (voice_id == "slt") return register_cmu_us_slt(nullptr);
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_pengpengenglish_FliteNativeBridge_nativeInit(JNIEnv *, jobject) {
    if (g_voice != nullptr) return JNI_TRUE;
    flite_init();
    g_voice = select_voice(g_voice_id);
    if (g_voice == nullptr) {
        g_last_error = "register voice failed: " + g_voice_id;
        return JNI_FALSE;
    }
    g_last_error = "";
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_pengpengenglish_FliteNativeBridge_nativeLastError(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_pengpengenglish_FliteNativeBridge_nativeSpeak(JNIEnv *env, jobject, jstring text, jstring, jstring out_path) {
    if (g_voice == nullptr) {
        g_last_error = "Flite not initialized";
        return JNI_FALSE;
    }
    if (text == nullptr || out_path == nullptr) {
        g_last_error = "Text or output path is null";
        return JNI_FALSE;
    }
    const char *text_c = env->GetStringUTFChars(text, nullptr);
    const char *path_c = env->GetStringUTFChars(out_path, nullptr);
    const float duration = flite_text_to_speech(text_c, g_voice, path_c);
    env->ReleaseStringUTFChars(text, text_c);
    env->ReleaseStringUTFChars(out_path, path_c);
    if (duration <= 0.0f) {
        g_last_error = "flite_text_to_speech failed";
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_pengpengenglish_FliteNativeBridge_nativeSetVoice(JNIEnv *env, jobject, jstring voice_id) {
    if (voice_id == nullptr) {
        g_last_error = "voice id is null";
        return JNI_FALSE;
    }
    const char *voice_c = env->GetStringUTFChars(voice_id, nullptr);
    const std::string target = voice_c;
    env->ReleaseStringUTFChars(voice_id, voice_c);

    cst_voice *voice = select_voice(target);
    if (voice == nullptr) {
        g_last_error = "unsupported flite voice: " + target;
        return JNI_FALSE;
    }
    g_voice = voice;
    g_voice_id = target;
    g_last_error = "";
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_pengpengenglish_FliteNativeBridge_nativeRelease(JNIEnv *, jobject) {}
