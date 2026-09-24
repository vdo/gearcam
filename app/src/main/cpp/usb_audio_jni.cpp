#include <jni.h>
#include <time.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "libuac.h"

namespace {

int64_t monotonic_ns() {
    timespec value{};
    clock_gettime(CLOCK_MONOTONIC, &value);
    return static_cast<int64_t>(value.tv_sec) * 1000000000LL + value.tv_nsec;
}

void throw_io(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("java/io/IOException");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

struct DirectSession {
    std::shared_ptr<uac::uac_context> context;
    std::shared_ptr<uac::uac_device_handle> device_handle;
    std::shared_ptr<uac::uac_stream_handle> stream_handle;
    const uac::uac_stream_if* stream_if = nullptr;
    std::unique_ptr<const uac::uac_audio_config_uncompressed> config;

    std::mutex lifecycle_mutex;
    std::mutex mutex;
    std::condition_variable available;
    std::vector<float> ring;
    size_t head = 0;
    size_t used = 0;
    int64_t first_frame = 0;
    int64_t written_frames = 0;
    int64_t anchor_frame = 0;
    int64_t anchor_ns = 0;
    int64_t overflow_frames = 0;
    bool stopping = false;

    int channels() const { return config == nullptr ? 0 : config->bChannelCount; }

    void receive(uint8_t* bytes, unsigned byte_count) {
        const int channel_count = channels();
        const int subframe = config->bSubframeSize;
        const bool floating = config->audioDataFormat == uac::UAC_FORMAT_DATA_IEEE_FLOAT;
        const unsigned stride = static_cast<unsigned>(channel_count * subframe);
        const unsigned frames = stride == 0 ? 0 : byte_count / stride;
        if (frames == 0) return;

        std::lock_guard<std::mutex> lock(mutex);
        if (stopping) return;
        for (unsigned frame = 0; frame < frames; ++frame) {
            if (used + static_cast<size_t>(channel_count) > ring.size()) {
                head = (head + channel_count) % ring.size();
                used -= channel_count;
                ++first_frame;
                ++overflow_frames;
            }
            for (int channel = 0; channel < channel_count; ++channel) {
                const uint8_t* source = bytes + frame * stride + channel * subframe;
                if (floating) {
                    float value;
                    std::memcpy(&value, source, sizeof(float));
                    ring[(head + used) % ring.size()] = std::isfinite(value) ? value : 0.0f; // device data: no NaN/Inf into the mix
                    ++used;
                    continue;
                }
                uint32_t raw = 0;
                for (int b = 0; b < subframe && b < 4; ++b)
                    raw |= static_cast<uint32_t>(source[b]) << (8 * b);
                /* UAC Type-I PCM is left-justified in its subframe. Sign-extend
                 * the container and preserve its precision in the floating-point mix. */
                const int container_bits = subframe * 8;
                int32_t sample = container_bits == 32 ? static_cast<int32_t>(raw) :
                        static_cast<int32_t>(raw << (32 - container_bits)) >> (32 - container_bits);
                ring[(head + used) % ring.size()] = static_cast<float>(
                        static_cast<double>(sample) / std::ldexp(1.0, container_bits - 1));
                ++used;
            }
        }
        written_frames += frames;
        anchor_frame = written_frames;
        anchor_ns = monotonic_ns();
        available.notify_one();
    }

    int read(float* output, int max_frames, int64_t timing[4]) {
        std::unique_lock<std::mutex> lock(mutex);
        available.wait_for(lock, std::chrono::milliseconds(250), [this] { return used > 0 || stopping; });
        if (used == 0) return stopping ? -1 : 0;
        const int frame_count = std::min<int>(max_frames, used / channels());
        timing[0] = first_frame;
        timing[1] = anchor_frame;
        timing[2] = anchor_ns;
        timing[3] = overflow_frames;
        const size_t samples = static_cast<size_t>(frame_count * channels());
        for (size_t i = 0; i < samples; ++i) output[i] = ring[(head + i) % ring.size()];
        head = (head + samples) % ring.size();
        used -= samples;
        first_frame += frame_count;
        return frame_count;
    }

    void stop() {
        std::lock_guard<std::mutex> lifecycle(lifecycle_mutex);
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (stopping) return;
            stopping = true;
        }
        available.notify_all();
        if (stream_handle != nullptr) stream_handle->stop();
        stream_handle.reset();
    }

    ~DirectSession() {
        stop();
        if (device_handle != nullptr) device_handle->close();
    }
};

DirectSession* from(jlong handle) {
    return reinterpret_cast<DirectSession*>(static_cast<intptr_t>(handle));
}

std::unique_ptr<const uac::uac_audio_config_uncompressed> choose_config(
        const uac::uac_stream_if& stream, int requested_channels, int sample_rate) {
    // 32-bit float keeps a float-recording interface's headroom; otherwise take the deepest PCM.
    for (auto format : {uac::UAC_FORMAT_DATA_IEEE_FLOAT, uac::UAC_FORMAT_DATA_PCM}) {
        auto channel_counts = stream.get_channel_counts(format);
        std::sort(channel_counts.rbegin(), channel_counts.rend());
        for (uint8_t channels : channel_counts) {
            if (channels == 0 || channels > 32) continue;
            if (requested_channels > 0 && channels != requested_channels) continue;
            auto config = stream.query_config_uncompressed(format, channels, static_cast<uint32_t>(sample_rate));
            if (config == nullptr) continue;
            if (format == uac::UAC_FORMAT_DATA_IEEE_FLOAT ? config->bSubframeSize == 4 && config->bBitResolution == 32 :
                config->bSubframeSize >= 1 && config->bSubframeSize <= 4 &&
                config->bBitResolution >= 8 && config->bBitResolution <= 32)
                return config;
        }
    }
    return nullptr;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_net_sourceforge_opencamera_audio_DirectUsbCapture_nativeOpen(
        JNIEnv* env, jclass, jint fd, jint requested_channels, jint sample_rate) {
    try {
        auto session = std::make_unique<DirectSession>();
        session->context = uac::uac_context::create();
        session->device_handle = session->context->wrap(fd);
        auto device = session->device_handle->get_device();
        auto routes = device->query_audio_routes(uac::UAC_TERMINAL_ANY, uac::UAC_TERMINAL_USB_STREAMING);
        if (routes.empty()) throw std::runtime_error("This USB device has no audio input stream");

        for (const auto& route : routes) {
            const auto& stream = device->get_stream_interface(route.get());
            session->config = choose_config(stream, requested_channels, sample_rate);
            if (session->config != nullptr) {
                session->stream_if = &stream;
                break;
            }
        }
        if (session->config == nullptr)
            throw std::runtime_error("The USB input has no 48 kHz PCM or float format for the selected channel count");
        session->ring.resize(static_cast<size_t>(sample_rate * session->channels() * 3));
        return static_cast<jlong>(reinterpret_cast<intptr_t>(session.release()));
    } catch (const std::exception& error) {
        throw_io(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_net_sourceforge_opencamera_audio_DirectUsbCapture_nativeChannels(JNIEnv*, jclass, jlong handle) {
    return from(handle)->channels();
}

extern "C" JNIEXPORT void JNICALL
Java_net_sourceforge_opencamera_audio_DirectUsbCapture_nativeStart(JNIEnv* env, jclass, jlong handle) {
    try {
        DirectSession* session = from(handle);
        session->stream_handle = session->device_handle->start_streaming(
                *session->stream_if, *session->config,
                [session](uint8_t* data, unsigned size) { session->receive(data, size); }, 24);
    } catch (const std::exception& error) {
        throw_io(env, error.what());
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_net_sourceforge_opencamera_audio_DirectUsbCapture_nativeRead(
        JNIEnv* env, jclass, jlong handle, jfloatArray samples, jint max_frames, jlongArray timing) {
    if (handle == 0 || samples == nullptr || timing == nullptr) return -1;
    DirectSession* session = from(handle);
    std::lock_guard<std::mutex> lifecycle(session->lifecycle_mutex);
    if (session->stream_handle != nullptr &&
        session->stream_handle->check_streaming_error() != uac::UAC_NO_ERROR) {
        throw_io(env, "USB isochronous capture stopped");
        return -1;
    }
    const int max_by_array = env->GetArrayLength(samples) / session->channels();
    max_frames = std::min(max_frames, max_by_array);
    std::vector<float> block(static_cast<size_t>(max_frames * session->channels()));
    int64_t values[4]{};
    int count = session->read(block.data(), max_frames, values);
    if (count > 0) {
        env->SetFloatArrayRegion(samples, 0, count * session->channels(),
                                 reinterpret_cast<const jfloat*>(block.data()));
        env->SetLongArrayRegion(timing, 0, 4, reinterpret_cast<const jlong*>(values));
    }
    return count;
}

extern "C" JNIEXPORT void JNICALL
Java_net_sourceforge_opencamera_audio_DirectUsbCapture_nativeStop(JNIEnv*, jclass, jlong handle) {
    if (handle != 0) from(handle)->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_net_sourceforge_opencamera_audio_DirectUsbCapture_nativeClose(JNIEnv*, jclass, jlong handle) {
    delete from(handle);
}
