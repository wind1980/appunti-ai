// Ponte JNI tra Kotlin (it.edoardo.appunti.whisper.WhisperNative) e whisper.cpp.

#include <jni.h>
#include <atomic>
#include <string>
#include <cstdint>

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AppuntiWhisper", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "AppuntiWhisper", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#define LOGW(...) (fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#endif

namespace {

// Richiesta di annullamento immediato da Kotlin (WhisperNative.cancel()).
std::atomic<bool> g_cancel{false};

struct CallbackData {
    JNIEnv *env;
    jobject listener;
    jmethodID onProgress;
    jmethodID isCancelled;
    std::atomic<bool> cancelled{false};
};

// Chiamato da whisper_full sul thread che ha avviato la trascrizione:
// qui si può usare JNIEnv in sicurezza.
void progress_cb(struct whisper_context *, struct whisper_state *, int progress, void *user_data) {
    auto *cb = static_cast<CallbackData *>(user_data);
    if (cb == nullptr || cb->listener == nullptr) return;
    cb->env->CallVoidMethod(cb->listener, cb->onProgress, (jint) progress);
    jboolean c = cb->env->CallBooleanMethod(cb->listener, cb->isCancelled);
    if (cb->env->ExceptionCheck()) {
        cb->env->ExceptionClear();
    }
    if (c) cb->cancelled.store(true);
}

// Può essere chiamato da ggml durante il calcolo: legge solo un flag atomico,
// senza toccare la JVM.
bool abort_cb(void *user_data) {
    auto *cb = static_cast<CallbackData *>(user_data);
    if (g_cancel.load()) return true;
    return cb != nullptr && cb->cancelled.load();
}

// Sostituisce tabulazioni e a capo nel testo dei segmenti: il risultato
// viene restituito come righe "t0_ms \t t1_ms \t testo".
std::string clean(const char *text) {
    std::string s = text ? text : "";
    for (char &ch : s) {
        if (ch == '\t' || ch == '\n' || ch == '\r') ch = ' ';
    }
    return s;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_it_edoardo_appunti_whisper_WhisperNative_initContext(JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (ctx == nullptr) {
        LOGW("Impossibile caricare il modello: %s", path);
    } else {
        LOGI("Modello caricato: %s", path);
    }
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_it_edoardo_appunti_whisper_WhisperNative_freeContext(JNIEnv *, jobject, jlong ptr) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ptr);
    if (ctx != nullptr) whisper_free(ctx);
}

JNIEXPORT void JNICALL
Java_it_edoardo_appunti_whisper_WhisperNative_cancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

JNIEXPORT jstring JNICALL
Java_it_edoardo_appunti_whisper_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

// Trascrive un blocco di audio a 16 kHz mono (float -1..1).
// Restituisce le righe "t0_ms\tt1_ms\ttesto\n" in UTF-8 (byte grezzi, decodificati in Kotlin:
// NewStringUTF non accetta UTF-8 standard con emoji o caratteri spezzati),
// oppure null in caso di errore o annullamento.
JNIEXPORT jbyteArray JNICALL
Java_it_edoardo_appunti_whisper_WhisperNative_transcribe(
        JNIEnv *env, jobject,
        jlong ptr, jfloatArray samples, jint numSamples,
        jstring language, jint threads, jstring initialPrompt,
        jstring vadModelPath, jboolean noFallback, jobject listener) {

    auto *ctx = reinterpret_cast<struct whisper_context *>(ptr);
    if (ctx == nullptr) return nullptr;

    CallbackData cb;
    cb.env = env;
    cb.listener = listener;
    jclass cls = env->GetObjectClass(listener);
    cb.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
    cb.isCancelled = env->GetMethodID(cls, "isCancelled", "()Z");
    env->DeleteLocalRef(cls);
    if (cb.onProgress == nullptr || cb.isCancelled == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }

    const char *lang = env->GetStringUTFChars(language, nullptr);
    const char *prompt = initialPrompt ? env->GetStringUTFChars(initialPrompt, nullptr) : nullptr;
    const char *vad = vadModelPath ? env->GetStringUTFChars(vadModelPath, nullptr) : nullptr;

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = (lang != nullptr && lang[0] != '\0') ? lang : "auto";
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    params.initial_prompt = (prompt != nullptr && prompt[0] != '\0') ? prompt : nullptr;
    params.progress_callback = progress_cb;
    params.progress_callback_user_data = &cb;
    params.abort_callback = abort_cb;
    params.abort_callback_user_data = &cb;

    // Niente ritentativi a temperatura più alta sui pezzi difficili: molto più veloce
    // sull'audio rumoroso, a costo di qualche errore in più proprio su quei pezzi.
    if (noFallback) params.temperature_inc = 0.0f;

    // Rilevatore di voce (Silero): trascrive solo le parti parlate, saltando silenzi e pause.
    // whisper.cpp riporta i tempi dei segmenti sull'audio originale.
    if (vad != nullptr && vad[0] != '\0') {
        params.vad = true;
        params.vad_model_path = vad;
        params.vad_params = whisper_vad_default_params();
        params.vad_params.min_silence_duration_ms = 300;
        params.vad_params.speech_pad_ms = 200;
    }

    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    int n = numSamples;
    jsize arrLen = env->GetArrayLength(samples);
    if (n > arrLen) n = arrLen;

    g_cancel.store(false);
    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, params, data, n);
    if (rc != 0 && params.vad && !cb.cancelled.load() && !g_cancel.load()) {
        // Se il rilevatore di voce non funziona (file mancante o rovinato) si trascrive tutto.
        LOGW("VAD non riuscito (codice %d): trascrivo senza", rc);
        params.vad = false;
        params.vad_model_path = nullptr;
        rc = whisper_full(ctx, params, data, n);
    }
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    jbyteArray result = nullptr;
    const bool cancelled = cb.cancelled.load() || g_cancel.load();
    if (rc != 0 || cancelled) {
        LOGW("whisper_full terminato con codice %d (annullato: %d)", rc, (int) cancelled);
    } else {
        std::string out;
        const int nseg = whisper_full_n_segments(ctx);
        for (int i = 0; i < nseg; ++i) {
            // t0/t1 sono in centesimi di secondo.
            const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
            const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
            out += std::to_string(t0);
            out += '\t';
            out += std::to_string(t1);
            out += '\t';
            out += clean(whisper_full_get_segment_text(ctx, i));
            out += '\n';
        }
        result = env->NewByteArray((jsize) out.size());
        if (result != nullptr) {
            env->SetByteArrayRegion(result, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
        }
    }

    env->ReleaseStringUTFChars(language, lang);
    if (prompt != nullptr) env->ReleaseStringUTFChars(initialPrompt, prompt);
    if (vad != nullptr) env->ReleaseStringUTFChars(vadModelPath, vad);
    return result;
}

} // extern "C"
