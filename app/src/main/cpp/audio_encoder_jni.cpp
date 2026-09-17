#include <jni.h>
#include <lame.h>
#include <FLAC/stream_encoder.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <unistd.h>
#include <memory>

namespace {
struct Encoder {
    FILE* file = nullptr;
    lame_t mp3 = nullptr;
    FLAC__StreamEncoder* flac = nullptr;
    float pcm[960];
    FLAC__int32 integer[960];
    unsigned char bytes[16384];
    ~Encoder() {
        if (mp3) lame_close(mp3);
        if (flac) FLAC__stream_encoder_delete(flac);
        if (file) fclose(file);
    }
};
void error(JNIEnv* env, const char* message) { env->ThrowNew(env->FindClass("java/io/IOException"), message); }
FLAC__StreamEncoderWriteStatus writeFlac(const FLAC__StreamEncoder*, const FLAC__byte data[], size_t bytes, unsigned, unsigned, void* opaque) {
    auto* e = static_cast<Encoder*>(opaque);
    return fwrite(data, 1, bytes, e->file) == bytes ? FLAC__STREAM_ENCODER_WRITE_STATUS_OK : FLAC__STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR;
}
FLAC__StreamEncoderSeekStatus seekFlac(const FLAC__StreamEncoder*, FLAC__uint64 offset, void* opaque) {
    return fseeko(static_cast<Encoder*>(opaque)->file, static_cast<off_t>(offset), SEEK_SET) == 0 ? FLAC__STREAM_ENCODER_SEEK_STATUS_OK : FLAC__STREAM_ENCODER_SEEK_STATUS_ERROR;
}
FLAC__StreamEncoderTellStatus tellFlac(const FLAC__StreamEncoder*, FLAC__uint64* offset, void* opaque) {
    off_t position = ftello(static_cast<Encoder*>(opaque)->file);
    if (position < 0) return FLAC__STREAM_ENCODER_TELL_STATUS_ERROR;
    *offset = position; return FLAC__STREAM_ENCODER_TELL_STATUS_OK;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_net_sourceforge_opencamera_audio_AudioFileEncoder_nativeOpen(JNIEnv* env, jclass, jint descriptor, jboolean flac) {
    std::unique_ptr<Encoder> e(new Encoder());
    int fd = dup(descriptor);
    if (fd < 0) { error(env, "Cannot open audio destination"); return 0; }
    e->file = fdopen(fd, "w+b");
    if (!e->file) { close(fd); error(env, "Cannot open audio output stream"); return 0; }
    if (fseeko(e->file, 0, SEEK_SET) != 0) { error(env, "Choose a local or SD-card folder that supports seeking"); return 0; }
    if (flac) {
        e->flac = FLAC__stream_encoder_new();
        if (!e->flac || !FLAC__stream_encoder_set_channels(e->flac, 2) ||
            !FLAC__stream_encoder_set_bits_per_sample(e->flac, 24) ||
            !FLAC__stream_encoder_set_sample_rate(e->flac, 48000) ||
            !FLAC__stream_encoder_set_compression_level(e->flac, 5) ||
            FLAC__stream_encoder_init_stream(e->flac, writeFlac, seekFlac, tellFlac, nullptr, e.get()) != FLAC__STREAM_ENCODER_INIT_STATUS_OK) {
            error(env, "Cannot start FLAC encoder"); return 0;
        }
    } else {
        e->mp3 = lame_init();
        if (!e->mp3) { error(env, "Cannot create MP3 encoder"); return 0; }
        lame_set_in_samplerate(e->mp3, 48000); lame_set_out_samplerate(e->mp3, 48000);
        lame_set_num_channels(e->mp3, 2); lame_set_brate(e->mp3, 320);
        lame_set_quality(e->mp3, 2); lame_set_mode(e->mp3, JOINT_STEREO);
        if (lame_init_params(e->mp3) < 0) { error(env, "Cannot start MP3 encoder"); return 0; }
    }
    return reinterpret_cast<jlong>(e.release());
}

extern "C" JNIEXPORT void JNICALL
Java_net_sourceforge_opencamera_audio_AudioFileEncoder_nativeWrite(JNIEnv* env, jclass, jlong handle, jfloatArray samples, jint frames) {
    auto* e = reinterpret_cast<Encoder*>(handle);
    if (!e || frames < 0 || env->GetArrayLength(samples) < frames * 2) { error(env, "Invalid encoder input"); return; }
    for (int start = 0; start < frames; start += 480) {
        int count = std::min(480, frames - start);
        env->GetFloatArrayRegion(samples, start * 2, count * 2, e->pcm);
        if (env->ExceptionCheck()) return;
        for (int i = 0; i < count * 2; i++) {
            float value = std::isfinite(e->pcm[i]) ? std::max(-1.0f, std::min(1.0f, e->pcm[i])) : 0;
            e->pcm[i] = value;
            e->integer[i] = std::max(-8388608L, std::min(8388607L, static_cast<long>(std::floor(static_cast<double>(value) * 8388608.0 + 0.5))));
        }
        if (e->flac) {
            if (!FLAC__stream_encoder_process_interleaved(e->flac, e->integer, count)) { error(env, "FLAC write failed; check save location and free space"); return; }
        } else {
            int bytes = lame_encode_buffer_interleaved_ieee_float(e->mp3, e->pcm, count, e->bytes, sizeof(e->bytes));
            if (bytes < 0 || fwrite(e->bytes, 1, bytes, e->file) != static_cast<size_t>(bytes)) { error(env, "MP3 write failed; check save location and free space"); return; }
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_sourceforge_opencamera_audio_AudioFileEncoder_nativeFinish(JNIEnv* env, jclass, jlong handle) {
    std::unique_ptr<Encoder> e(reinterpret_cast<Encoder*>(handle));
    if (!e) return;
    bool ok = true;
    if (e->flac) ok = FLAC__stream_encoder_finish(e->flac);
    else {
        int bytes = lame_encode_flush(e->mp3, e->bytes, sizeof(e->bytes));
        ok = bytes >= 0 && fwrite(e->bytes, 1, bytes, e->file) == static_cast<size_t>(bytes);
        if (ok) lame_mp3_tags_fid(e->mp3, e->file);
    }
    ok = fflush(e->file) == 0 && !ferror(e->file) && ok;
    if (fclose(e->file) != 0) ok = false;
    e->file = nullptr;
    if (!ok) error(env, "Could not finish audio file; check the save destination");
}
