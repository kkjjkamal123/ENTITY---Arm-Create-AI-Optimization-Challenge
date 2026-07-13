#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <vector>
#include <algorithm>
#include <cstdio>
#include <unistd.h>
#include <sched.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"
#include "ggml-cpu.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   DEFAULT_CONTEXT_SIZE    = 4096;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;
static cpu_set_t g_fast_cpus;
static int       g_fast_count = 0;

// Split thread pools with explicit CPU masks: generation runs on the big cores
// only (memory-bandwidth bound), prompt processing widens to all cores (compute
// bound). ggml applies each pool's cpumask per phase, so the two never
// oversubscribe the affinity set the way a single widened mask would.
static ggml_threadpool_t g_tp_gen   = nullptr;
static ggml_threadpool_t g_tp_batch = nullptr;

// With GGML_BACKEND_DL the CPU backend is a dynamically loaded module, so its
// threadpool functions are not linkable symbols; resolve them from the backend
// registry at init (null when unavailable -> no-threadpool fallback).
static decltype(ggml_threadpool_new)  * g_threadpool_new_fn  = nullptr;
static decltype(ggml_threadpool_free) * g_threadpool_free_fn = nullptr;

// Returned for tokens still assembling a multi-byte UTF-8 sequence, so the
// per-token path skips a Java string allocation.
static jstring g_empty_jstring = nullptr;

// Runtime-tunable config, set from Kotlin via configure()/setSampler().
// The app either fills these from the user's manual settings or, in auto mode,
// picks an adaptive context per model + free RAM. n_threads == 0 means "auto".
static int   g_n_ctx     = DEFAULT_CONTEXT_SIZE;
static int   g_n_threads = 0;
static float g_temp      = DEFAULT_SAMPLER_TEMP;
static int   g_top_k     = 40;
static float g_top_p     = 0.95f;

// CPU indices ranked by max cpufreq, fastest first.
// On big.LITTLE the leading entries are the performance cluster (e.g. the A78
// cores), the trailing ones the efficiency cluster.
static std::vector<int> ranked_fast_cpus() {
    const int ncpu = (int) sysconf(_SC_NPROCESSORS_CONF);
    std::vector<std::pair<long, int>> cores;
    for (int i = 0; i < ncpu; i++) {
        char path[128];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        long khz = 0;
        if (FILE *f = fopen(path, "r")) {
            if (fscanf(f, "%ld", &khz) != 1) { khz = 0; }
            fclose(f);
        }
        cores.emplace_back(khz, i);
    }
    std::sort(cores.begin(), cores.end(),
              [](const auto &a, const auto &b) { return a.first > b.first; });

    std::vector<int> ranked;
    ranked.reserve(cores.size());
    for (const auto &c : cores) { ranked.push_back(c.second); }
    return ranked;
}

// Build a CPU set of the n fastest cores; pinning to it keeps inference off the
// little cores.
static void build_fast_cpu_set(int want) {
    const std::vector<int> ranked = ranked_fast_cpus();
    CPU_ZERO(&g_fast_cpus);
    g_fast_count = 0;
    for (int i = 0; i < (int) ranked.size() && g_fast_count < want; i++) {
        CPU_SET(ranked[i], &g_fast_cpus);
        g_fast_count++;
    }
}

// A ggml thread pool of `want` threads pinned to the `want` fastest cores.
// Returns null on failure (caller falls back to the auto pool).
static ggml_threadpool_t new_threadpool_on_fast_cores(int want) {
    if (want <= 0 || !g_threadpool_new_fn || !g_threadpool_free_fn) { return nullptr; }
    const std::vector<int> ranked = ranked_fast_cpus();
    ggml_threadpool_params tpp;
    ggml_threadpool_params_init(&tpp, want);
    int set = 0;
    for (int i = 0; i < (int) ranked.size() && set < want; i++) {
        if (ranked[i] >= 0 && ranked[i] < GGML_MAX_N_THREADS) {
            tpp.cpumask[ranked[i]] = true;
            set++;
        }
    }
    return g_threadpool_new_fn(&tpp);
}

// Pin the calling thread to the fast cores. ggml spawns its worker threads
// lazily from whichever thread first runs a decode, and they inherit this
// affinity mask, so calling this on every inference entry point keeps the
// whole compute on the performance cluster even if the coroutine dispatcher
// migrates us to a different IO thread.
static void pin_to_fast_cores() {
    if (g_fast_count > 0) {
        sched_setaffinity(0, sizeof(cpu_set_t), &g_fast_cpus);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();

    if (auto *cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
        auto *reg = ggml_backend_dev_backend_reg(cpu_dev);
        g_threadpool_new_fn = (decltype(ggml_threadpool_new) *)
                ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new");
        g_threadpool_free_fn = (decltype(ggml_threadpool_free) *)
                ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free");
    }
    if (!g_threadpool_new_fn || !g_threadpool_free_fn) {
        LOGw("%s: threadpool functions unavailable, using default thread scheduling", __func__);
    }

    if (!g_empty_jstring) {
        if (jstring empty = env->NewStringUTF("")) {
            g_empty_jstring = (jstring) env->NewGlobalRef(empty);
            env->DeleteLocalRef(empty);
        }
    }
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}

static llama_context *init_context(llama_model *model, int n_ctx_override = -1) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Thread count: honour the app's setting, else auto from online core count.
    const int n_online = (int) sysconf(_SC_NPROCESSORS_ONLN);
    const int n_threads = g_n_threads > 0
            ? std::max(1, std::min(n_online, g_n_threads))
            : std::max(N_THREADS_MIN, std::min(N_THREADS_MAX, n_online - N_THREADS_HEADROOM));

    // Context size: explicit override (benchmark) wins, else the app's value.
    int n_ctx = n_ctx_override > 0 ? n_ctx_override
                                   : (g_n_ctx > 0 ? g_n_ctx : DEFAULT_CONTEXT_SIZE);
    LOGi("%s: %d threads, ctx %d", __func__, n_threads, n_ctx);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
        return context;
    }

    // Record the context size actually allocated so the completion-loop bounds match.
    g_n_ctx = (int) llama_n_ctx(context);

    build_fast_cpu_set(n_threads);
    pin_to_fast_cores();
    LOGi("%s: pinned inference to %d fast cores", __func__, g_fast_count);
    return context;
}

static common_sampler *new_sampler() {
    common_params_sampling sparams;
    sparams.temp  = g_temp;
    sparams.top_k = g_top_k;
    sparams.top_p = g_top_p;
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    auto *context = init_context(g_model);
    if (!context) { return 1; }

    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    if (!g_chat_templates) {
        LOGe("%s: failed to init chat templates", __func__);
        llama_batch_free(g_batch);
        g_batch = {};
        llama_free(context);
        return 2;
    }
    g_sampler = new_sampler();
    if (!g_sampler) {
        LOGe("%s: failed to init sampler", __func__);
        g_chat_templates.reset();
        llama_batch_free(g_batch);
        g_batch = {};
        llama_free(context);
        return 3;
    }
    g_context = context;

    // Generation stays on the big cores; prompt processing widens to every
    // online core in auto mode. A manual thread count is honoured for both.
    const int n_gen    = (int) llama_n_threads(g_context);
    const int n_online = (int) sysconf(_SC_NPROCESSORS_ONLN);
    const int n_pp     = g_n_threads > 0 ? n_gen : std::max(n_gen, n_online);
    g_tp_gen = new_threadpool_on_fast_cores(n_gen);
    if (g_tp_gen) {
        if (n_pp > n_gen) {
            g_tp_batch = new_threadpool_on_fast_cores(n_pp);
        }
        if (g_tp_batch) {
            llama_attach_threadpool(g_context, g_tp_gen, g_tp_batch);
            llama_set_n_threads(g_context, n_gen, n_pp);
            LOGi("%s: gen %d threads (big cores), pp %d threads (all cores)",
                 __func__, n_gen, n_pp);
        } else {
            llama_attach_threadpool(g_context, g_tp_gen, g_tp_gen);
            LOGi("%s: %d threads (big cores) for gen and pp", __func__, n_gen);
        }
    }
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_configure(
        JNIEnv * /*env*/, jobject /*unused*/,
        jint nCtx, jint nThreads, jfloat temp, jint topK, jfloat topP) {
    if (nCtx > 0) { g_n_ctx = nCtx; }
    g_n_threads = nThreads < 0 ? 0 : nThreads;
    g_temp  = temp;
    g_top_k = topK;
    g_top_p = topP;
    LOGi("%s: ctx=%d threads=%d temp=%.2f topK=%d topP=%.2f",
         __func__, g_n_ctx, g_n_threads, g_temp, g_top_k, g_top_p);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setSampler(
        JNIEnv * /*env*/, jobject /*unused*/, jfloat temp, jint topK, jfloat topP) {
    g_temp  = temp;
    g_top_k = topK;
    g_top_p = topP;
    if (g_sampler && g_model) {
        if (auto *sampler = new_sampler()) {
            common_sampler_free(g_sampler);
            g_sampler = sampler;
        }
    }
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    // The benchmark builds its own context (n_ctx = pp) with its own thread
    // count, so init_context overwrites the global ctx tracker AND the core
    // affinity set. Save both so the chat's context bounds and its big-core
    // pinning are restored after the benchmark returns.
    const int        saved_n_ctx     = g_n_ctx;
    const cpu_set_t  saved_fast_cpus = g_fast_cpus;
    const int        saved_fast_count = g_fast_count;
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    // Same split pools the chat path attaches in prepare(): generation on the fast
    // cores, prompt processing widened to every online core when the thread count is
    // auto. Without this the benchmark would measure a config the app never runs.
    // Local handles — g_tp_gen/g_tp_batch belong to the chat context.
    const int n_gen    = (int) llama_n_threads(context);
    const int n_online = (int) sysconf(_SC_NPROCESSORS_ONLN);
    const int n_pp     = g_n_threads > 0 ? n_gen : std::max(n_gen, n_online);
    ggml_threadpool_t tp_gen   = new_threadpool_on_fast_cores(n_gen);
    ggml_threadpool_t tp_batch = (tp_gen && n_pp > n_gen) ? new_threadpool_on_fast_cores(n_pp) : nullptr;
    if (tp_gen && tp_batch) {
        llama_attach_threadpool(context, tp_gen, tp_batch);
        llama_set_n_threads(context, n_gen, n_pp);
        LOGi("%s: gen %d threads (fast cores), pp %d threads (all cores)", __func__, n_gen, n_pp);
    } else if (tp_gen) {
        llama_attach_threadpool(context, tp_gen, tp_gen);
        LOGi("%s: %d threads (fast cores) for gen and pp", __func__, n_gen);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }

            if (llama_decode(context, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);
    if (g_threadpool_free_fn) {
        if (tp_batch) { g_threadpool_free_fn(tp_batch); }
        if (tp_gen)   { g_threadpool_free_fn(tp_gen);   }
    }

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";

    // Restore the chat's context-size tracker and big-core affinity set so the
    // next chat decode re-pins to the original cores, not this bench's set.
    g_n_ctx      = saved_n_ctx;
    g_fast_cpus  = saved_fast_cpus;
    g_fast_count = saved_fast_count;
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache && g_context)
        llama_memory_clear(llama_get_memory(g_context), false);
}

/**
 * TODO-hyin: implement sliding-window version as a better alternative
 *
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 */
static int shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    if (n_discard <= 0) { return 0; }
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
    return n_discard;
}

static std::string chat_add_and_format(const std::string &role, const std::string &content,
                                       const bool add_generation_prompt) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, add_generation_prompt, /* use_jinja */ false);
    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 */
static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
}

// Decodes `tokens` starting at the live current_position, advancing it as it
// goes. A batch that would overflow the context triggers a shift first, so the
// running position stays valid across the trim (older turns are discarded, the
// system-prompt prefix is preserved).
static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const bool compute_last_logit = false) {
    pin_to_fast_cores();
    const int n_tokens = (int) tokens.size();
    LOGd("%s: Decode %d tokens starting at position %d", __func__, n_tokens, current_position);
    for (int i = 0; i < n_tokens; i += BATCH_SIZE) {
        const int cur_batch_size = std::min(n_tokens - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Shift context if current batch cannot fit into the context
        if (current_position + cur_batch_size >= g_n_ctx - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            shift_context();
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const bool want_logit = compute_last_logit && (i + j == n_tokens - 1);
            common_batch_add(batch, token_id, current_position, {0}, want_logit);
            current_position++;
        }

        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt, false);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Handle context overflow
    const int max_batch_size = g_n_ctx - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Decode system tokens in batches (advances current_position)
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Mark the end of the system-prompt prefix (preserved across context shifts)
    system_prompt_position = current_position;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    // Reset short-term states
    reset_short_term_states();

    // Obtain and tokenize user prompt
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
    std::string formatted_user_prompt(user_prompt);

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt, true);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Decode formatted user prompts
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Ensure user prompt doesn't exceed the context size by truncating if necessary.
    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = g_n_ctx - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }

    // Decode user tokens in batches (advances current_position)
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Cap generation at exactly n_predict new tokens from the current position.
    stop_generation_position = current_position + n_predict;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_primeHistoryNative(
        JNIEnv *env,
        jobject /*unused*/,
        jobjectArray jroles,
        jobjectArray jtexts
) {
    if (!g_context) { return 1; }
    reset_short_term_states();

    const int n_turns = jroles ? env->GetArrayLength(jroles) : 0;
    if (n_turns == 0) { return 0; }

    // Rebuild KV state on top of the already-decoded system prompt. Drop any
    // stale post-system tokens and chat history so a re-prime starts clean.
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, -1);
    current_position = system_prompt_position;
    if (!chat_msgs.empty() && chat_msgs.front().role == ROLE_SYSTEM) {
        chat_msgs.resize(1);
    } else {
        chat_msgs.clear();
    }

    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    const int  max_batch_size    = g_n_ctx - OVERFLOW_HEADROOM;

    // Format + tokenize every turn through the same chat-template path used for
    // live turns, keeping each turn's tokens so we can pick a fitting suffix.
    // No generation prompt anywhere: the concatenated diffs must equal the full
    // template expansion, the next live user turn appends its own.
    std::vector<llama_tokens> turn_tokens;
    turn_tokens.reserve(n_turns);
    for (int t = 0; t < n_turns; t++) {
        auto *jrole = (jstring) env->GetObjectArrayElement(jroles, t);
        auto *jtext = (jstring) env->GetObjectArrayElement(jtexts, t);
        const auto *role = jrole ? env->GetStringUTFChars(jrole, nullptr) : nullptr;
        const auto *text = jtext ? env->GetStringUTFChars(jtext, nullptr) : nullptr;
        if (!role || !text) {
            if (role) { env->ReleaseStringUTFChars(jrole, role); }
            if (text) { env->ReleaseStringUTFChars(jtext, text); }
            LOGe("%s: failed to read turn %d", __func__, t);
            return 3;
        }
        std::string formatted(text);
        if (has_chat_template) {
            formatted = chat_add_and_format(role, text, false);
        }
        env->ReleaseStringUTFChars(jrole, role);
        env->ReleaseStringUTFChars(jtext, text);
        env->DeleteLocalRef(jrole);
        env->DeleteLocalRef(jtext);
        turn_tokens.push_back(
                common_tokenize(g_context, formatted, has_chat_template, has_chat_template));
    }

    // Keep the system prompt plus the most recent turns that fit the context.
    const int budget = max_batch_size - system_prompt_position;
    int start = n_turns;
    int running = 0;
    for (int t = n_turns - 1; t >= 0; t--) {
        running += (int) turn_tokens[t].size();
        if (running > budget) { break; }
        start = t;
    }
    if (start == n_turns) { start = n_turns - 1; }

    // Decode the fitting suffix oldest -> newest without generating.
    for (int t = start; t < n_turns; t++) {
        if (decode_tokens_in_batches(g_context, g_batch, turn_tokens[t])) {
            LOGe("%s: llama_decode() failed!", __func__);
            return 2;
        }
    }
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    pin_to_fast_cores();
    // Infinite text generation via context shifting. The stop position tracks
    // the trim so the n_predict budget stays correct after a shift.
    if (current_position >= g_n_ctx - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        stop_generation_position -= shift_context();
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    // Update position
    current_position++;

    // Stop if next token is EOG
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str(), false);
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = g_empty_jstring ? g_empty_jstring : env->NewStringUTF("");
    }
    return result;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Free up resources
    common_sampler_free(g_sampler);
    g_sampler = nullptr;
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    g_batch = {};
    llama_free(g_context);
    g_context = nullptr;
    if (g_tp_batch) { g_threadpool_free_fn(g_tp_batch); g_tp_batch = nullptr; }
    if (g_tp_gen)   { g_threadpool_free_fn(g_tp_gen);   g_tp_gen   = nullptr; }
    llama_model_free(g_model);
    g_model = nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
