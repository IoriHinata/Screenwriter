#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include "llama.h"

namespace {
std::mutex g_mutex;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
llama_sampler * g_sampler = nullptr;
const llama_vocab * g_vocab = nullptr;
bool g_backend_initialized = false;
std::atomic<bool> g_loaded{false};

void unload_locked() {
    g_loaded.store(false);
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
}

std::string from_jstring(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_screenwriter_app_LocalLlamaEngine_nativeLoadModel(
        JNIEnv * env, jobject, jstring path, jint context_size, jint threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
    const std::string model_path = from_jstring(env, path);
    if (model_path.empty()) return JNI_FALSE;
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!g_model) return JNI_FALSE;

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(std::max(512, std::min(8192, static_cast<int>(context_size))));
    context_params.n_batch = 512;
    context_params.n_ubatch = 256;
    const int thread_count = std::max(1, std::min(8, static_cast<int>(threads)));
    context_params.n_threads = thread_count;
    context_params.n_threads_batch = thread_count;
    g_context = llama_init_from_model(g_model, context_params);
    if (!g_context) {
        unload_locked();
        return JNI_FALSE;
    }
    g_vocab = llama_model_get_vocab(g_model);
    auto sampler_params = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sampler_params);
    if (!g_sampler) {
        unload_locked();
        return JNI_FALSE;
    }
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.92f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.78f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    g_loaded.store(true);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_screenwriter_app_LocalLlamaEngine_nativeGenerate(
        JNIEnv * env, jobject, jstring prompt_value, jint max_tokens, jfloat temperature) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_context || !g_vocab || !g_sampler) {
        return env->NewStringUTF("ОШИБКА: сначала загрузи локальную модель GGUF в настройках.");
    }

    const std::string prompt = from_jstring(env, prompt_value);
    if (prompt.empty()) return env->NewStringUTF("");
    llama_memory_clear(llama_get_memory(g_context), true);

    if (g_sampler) llama_sampler_free(g_sampler);
    auto sampler_params = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sampler_params);
    if (!g_sampler) return env->NewStringUTF("ОШИБКА: не удалось создать сэмплер генерации.");
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.92f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(std::max(0.1f, std::min(1.5f, static_cast<float>(temperature)))));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    std::vector<llama_token> tokens(std::max(1024, static_cast<int>(prompt.size() / 2 + 16)));
    int32_t token_count = llama_tokenize(g_vocab, prompt.c_str(),
        static_cast<int32_t>(prompt.size()), tokens.data(),
        static_cast<int32_t>(tokens.size()), true, true);
    if (token_count < 0) {
        tokens.resize(static_cast<size_t>(-token_count));
        token_count = llama_tokenize(g_vocab, prompt.c_str(),
            static_cast<int32_t>(prompt.size()), tokens.data(),
            static_cast<int32_t>(tokens.size()), true, true);
    }
    if (token_count <= 0) return env->NewStringUTF("ОШИБКА: не удалось токенизировать контекст.");
    tokens.resize(static_cast<size_t>(token_count));

    const int context_limit = static_cast<int>(llama_n_ctx(g_context));
    const int reserve = std::max(64, std::min(1024, static_cast<int>(max_tokens)));
    const int prompt_limit = std::max(128, context_limit - reserve);
    if (static_cast<int>(tokens.size()) > prompt_limit) {
        // Keep both the opening system rules and the tail containing the author's command.
        // Trimming only from the front silently discarded the system prompt on long projects.
        const int prefix_count = std::min(512, std::max(128, prompt_limit / 3));
        const int suffix_count = prompt_limit - prefix_count;
        std::vector<llama_token> bounded;
        bounded.reserve(static_cast<size_t>(prompt_limit));
        bounded.insert(bounded.end(), tokens.begin(), tokens.begin() + prefix_count);
        bounded.insert(bounded.end(), tokens.end() - suffix_count, tokens.end());
        tokens.swap(bounded);
    }

    const int32_t batch_capacity = static_cast<int32_t>(std::min<uint32_t>(512, llama_n_batch(g_context)));
    llama_batch batch = llama_batch_init(batch_capacity, 0, 1);
    if (!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) {
        llama_batch_free(batch);
        return env->NewStringUTF("ОШИБКА: не удалось выделить память для контекста.");
    }
    for (size_t offset = 0; offset < tokens.size(); offset += static_cast<size_t>(batch_capacity)) {
        const size_t count = std::min(static_cast<size_t>(batch_capacity), tokens.size() - offset);
        batch.n_tokens = static_cast<int32_t>(count);
        for (size_t i = 0; i < count; ++i) {
            const size_t token_index = offset + i;
            batch.token[i] = tokens[token_index];
            batch.pos[i] = static_cast<llama_pos>(token_index);
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (token_index + 1 == tokens.size()) ? 1 : 0;
        }
        if (llama_decode(g_context, batch) != 0) {
            llama_batch_free(batch);
            return env->NewStringUTF("ОШИБКА: модель не смогла обработать контекст. Уменьши объём текста или размер окна.");
        }
    }

    std::string output;
    std::vector<char> piece(16 * 1024);
    const int generation_limit = std::max(1, std::min(1024, static_cast<int>(max_tokens)));
    for (int i = 0; i < generation_limit; ++i) {
        llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
        if (llama_vocab_is_eog(g_vocab, token)) break;
        int32_t piece_len = llama_token_to_piece(g_vocab, token, piece.data(),
            static_cast<int32_t>(piece.size()), 0, true);
        if (piece_len < 0) {
            piece.resize(static_cast<size_t>(-piece_len) + 1);
            piece_len = llama_token_to_piece(g_vocab, token, piece.data(),
                static_cast<int32_t>(piece.size()), 0, true);
        }
        if (piece_len > 0) output.append(piece.data(), static_cast<size_t>(piece_len));

        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = static_cast<llama_pos>(tokens.size() + static_cast<size_t>(i));
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        if (llama_decode(g_context, batch) != 0) break;
    }
    llama_batch_free(batch);
    if (output.empty()) output = "Модель завершила генерацию без текста. Попробуй изменить команду.";
    // JNI's modified UTF-8 accepts standard UTF-8 for Russian text.
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ru_screenwriter_app_LocalLlamaEngine_nativeUnloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ru_screenwriter_app_LocalLlamaEngine_nativeIsLoaded(JNIEnv *, jobject) {
    return g_loaded.load() ? JNI_TRUE : JNI_FALSE;
}
