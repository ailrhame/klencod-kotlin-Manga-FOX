#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MangaFoxLLM", __VA_ARGS__)

static std::mutex g_mutex;
static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static std::string g_last_error = "لم تبدأ عملية التحميل";

static void release_locked() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_manga_fox_MangaFoxLocalLlm_nativeLoadModel(JNIEnv *env, jobject,
                                                     jstring model_path,
                                                     jint threads, jint context_size) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();
    g_last_error.clear();
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.use_mmap = true;
    mp.use_mlock = false;
    LOGE("Loading GGUF model from app-private storage");
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(model_path, path);
    if (!g_model) {
        g_last_error = "فشل llama_model_load_from_file: لم يتمكن المحرك من قراءة GGUF أو تخصيص الذاكرة";
        LOGE("Model load failed: %s", g_last_error.c_str());
        return JNI_FALSE;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(std::max(1024, context_size));
    cp.n_batch = 512;
    cp.n_ubatch = 128;
    cp.n_threads = std::max(1, threads);
    cp.n_threads_batch = std::max(1, threads);
    LOGE("Creating context: n_ctx=%u n_batch=%u n_ubatch=%u threads=%d",
         cp.n_ctx, cp.n_batch, cp.n_ubatch, cp.n_threads);
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        g_last_error = "تم فتح ملف النموذج، لكن إنشاء سياق التشغيل فشل؛ قد تكون الذاكرة المتاحة لحظياً غير كافية";
        release_locked();
        LOGE("Context creation failed");
        return JNI_FALSE;
    }
    g_last_error = "OK";
    LOGE("Qwen model loaded successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_manga_fox_MangaFoxLocalLlm_nativeGenerate(JNIEnv *env, jobject,
                                                    jstring prompt_string,
                                                    jint max_tokens) {
    const char *prompt_chars = env->GetStringUTFChars(prompt_string, nullptr);
    if (!prompt_chars) return env->NewStringUTF("");
    const std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(prompt_string, prompt_chars);

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) return env->NewStringUTF("");
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(std::max<size_t>(prompt.size() + 32, 4096));
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                  tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n_tokens < 0) {
        tokens.resize(static_cast<size_t>(-n_tokens));
        n_tokens = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                  tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    }
    if (n_tokens <= 0 || static_cast<uint32_t>(n_tokens) >= llama_n_ctx(g_ctx))
        return env->NewStringUTF("");
    tokens.resize(static_cast<size_t>(n_tokens));

    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(g_ctx, batch) != 0) return env->NewStringUTF("");

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.25f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(0x4D46584));

    std::string output;
    std::vector<char> piece(8192);
    const int remaining = static_cast<int>(llama_n_ctx(g_ctx)) - n_tokens - 8;
    const int limit = std::max(0, std::min({2048, static_cast<int>(max_tokens), remaining}));
    for (int i = 0; i < limit; ++i) {
        llama_token token = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        int n = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
        if (n > 0) output.append(piece.data(), static_cast<size_t>(n));
        llama_token next = token;
        llama_batch next_batch = llama_batch_get_one(&next, 1);
        if (llama_decode(g_ctx, next_batch) != 0) break;
    }
    llama_sampler_free(sampler);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_manga_fox_MangaFoxLocalLlm_nativeRelease(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_manga_fox_MangaFoxLocalLlm_nativeLastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}