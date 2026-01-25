#include <jni.h>
#include <string>
#include <vector>
#include <cstdio>
#include <cstring>
#include <sys/stat.h>

#include "whisper.h"

static bool file_size_at_least(const char* path, size_t min_bytes) {
    struct stat st;
    if (stat(path, &st) != 0) return false;
    return static_cast<size_t>(st.st_size) >= min_bytes;
}

static bool has_ggml_magic(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    unsigned char magic[4];
    const size_t n = fread(magic, 1, sizeof(magic), f);
    fclose(f);
    if (n != sizeof(magic)) return false;
    // ggml magic is 0x67676d6c; on disk (little-endian) it appears as "lmgg"
    const bool le = (magic[0] == 'l' && magic[1] == 'm' && magic[2] == 'g' && magic[3] == 'g');
    const bool be = (magic[0] == 'g' && magic[1] == 'g' && magic[2] == 'm' && magic[3] == 'l');
    return le || be;
}

static bool read_wav_mono_f32_16k(const char* path, std::vector<float>& out) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;

    auto read_u32 = [&](uint32_t& v)->bool { return fread(&v, 4, 1, f) == 1; };
    auto read_u16 = [&](uint16_t& v)->bool { return fread(&v, 2, 1, f) == 1; };

    char riff[4]; if (fread(riff, 1, 4, f) != 4) { fclose(f); return false; }
    uint32_t riff_sz; if (!read_u32(riff_sz)) { fclose(f); return false; }
    char wave[4]; if (fread(wave, 1, 4, f) != 4) { fclose(f); return false; }

    uint16_t audio_fmt=0, channels=0, bits=0;
    uint32_t sample_rate=0, data_sz=0;
    long data_pos = -1;

    while (!feof(f)) {
        char tag[4];
        if (fread(tag, 1, 4, f) != 4) break;
        uint32_t sz; if (!read_u32(sz)) break;

        if (memcmp(tag, "fmt ", 4) == 0) {
            read_u16(audio_fmt);
            read_u16(channels);
            read_u32(sample_rate);
            uint32_t byte_rate; read_u32(byte_rate);
            uint16_t block_align; read_u16(block_align);
            read_u16(bits);
            if (sz > 16) fseek(f, sz - 16, SEEK_CUR);
        } else if (memcmp(tag, "data", 4) == 0) {
            data_pos = ftell(f);
            data_sz = sz;
            fseek(f, sz, SEEK_CUR);
        } else {
            fseek(f, sz, SEEK_CUR);
        }

        if (data_pos >= 0 && sample_rate != 0) break;
    }

    if (data_pos < 0 || audio_fmt != 1 || channels != 1 || bits != 16 || sample_rate != 16000) {
        fclose(f);
        return false;
    }

    out.clear();
    out.reserve(data_sz / 2);

    fseek(f, data_pos, SEEK_SET);
    const size_t n = data_sz / 2;
    for (size_t i = 0; i < n; i++) {
        int16_t s;
        if (fread(&s, 2, 1, f) != 1) break;
        out.push_back((float)s / 32768.0f);
    }

    fclose(f);
    return !out.empty();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_offlinevoice_WhisperBridge_transcribeWav(
    JNIEnv* env, jobject /* this */, jstring wavPath, jstring modelPath) {

    const char* wav = env->GetStringUTFChars(wavPath, nullptr);
    const char* model = env->GetStringUTFChars(modelPath, nullptr);

    if (!file_size_at_least(model, 1 << 20) || !has_ggml_magic(model)) {
        env->ReleaseStringUTFChars(wavPath, wav);
        env->ReleaseStringUTFChars(modelPath, model);
        return env->NewStringUTF("(model invalid - expected ggml .bin)");
    }

    std::vector<float> pcmf32;
    if (!read_wav_mono_f32_16k(wav, pcmf32)) {
        env->ReleaseStringUTFChars(wavPath, wav);
        env->ReleaseStringUTFChars(modelPath, model);
        return env->NewStringUTF("(wav read failed - expected 16kHz mono PCM16)");
    }

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = false;
    whisper_context* ctx = whisper_init_from_file_with_params(model, cparams);
    if (!ctx) {
        env->ReleaseStringUTFChars(wavPath, wav);
        env->ReleaseStringUTFChars(modelPath, model);
        return env->NewStringUTF("(model load failed)");
    }

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress   = false;
    wparams.print_realtime   = false;
    wparams.print_timestamps = false;
    wparams.translate        = false;
    wparams.language         = "de";
    wparams.n_threads        = 4;

    const int rc = whisper_full(ctx, wparams, pcmf32.data(), (int)pcmf32.size());
    if (rc != 0) {
        whisper_free(ctx);
        env->ReleaseStringUTFChars(wavPath, wav);
        env->ReleaseStringUTFChars(modelPath, model);
        return env->NewStringUTF("(whisper_full failed)");
    }

    std::string text;
    const int nseg = whisper_full_n_segments(ctx);
    for (int i = 0; i < nseg; i++) {
        text += whisper_full_get_segment_text(ctx, i);
    }

    whisper_free(ctx);
    env->ReleaseStringUTFChars(wavPath, wav);
    env->ReleaseStringUTFChars(modelPath, model);

    if (text.empty()) text = "(empty)";
    return env->NewStringUTF(text.c_str());
}
