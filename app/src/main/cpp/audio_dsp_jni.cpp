#include <jni.h>

#include <algorithm>
#include <cmath>
#include <vector>

// Per-sample audio loops. Phones may run app Java interpreted (JIT off, debuggable builds), far too slow
// for a multichannel interface. Each function mirrors a Java fallback loop that the JVM unit tests
// exercise; built with -ffp-contract=off the results are identical for finite input (USB capture drops
// NaN/Inf; checked by MixerSpeedTest on device).

// TimedPcmBuffer.read(): timestamped ring -> output clock, windowed-sinc or linear fractional delay.
extern "C" JNIEXPORT jlong JNICALL
Java_net_sourceforge_opencamera_audio_TimedPcmBuffer_nativeRead(
        JNIEnv* env, jclass, jfloatArray samples_array, jlongArray times_array, jint head, jint size, jint capacity,
        jint channels, jfloatArray kernel_array, jlong first_ns, jint rate, jobjectArray output, jint offset, jint frames) {
    constexpr int radius = 16, taps = 32, phases = 1024;
    std::vector<float> block(static_cast<size_t>(frames) * channels); // channel-major
    int missing = 0, cursor = 0;
    auto* samples = static_cast<const float*>(env->GetPrimitiveArrayCritical(samples_array, nullptr));
    auto* times = static_cast<const jlong*>(env->GetPrimitiveArrayCritical(times_array, nullptr));
    auto* kernels = static_cast<const float*>(env->GetPrimitiveArrayCritical(kernel_array, nullptr));
    for (int f = 0; f < frames; ++f) {
        const jlong time = first_ns + std::llround(f * 1000000000.0 / rate);
        while (cursor + 1 < size && times[(head + cursor + 1) % capacity] <= time) ++cursor;
        const int current = (head + cursor) % capacity, next = (current + 1) % capacity;
        const bool available = cursor + 1 < size && time >= times[current] &&
                time <= times[next] && times[next] - times[current] < 5000000;
        if (!available) ++missing;
        const float fraction = available ?
                static_cast<float>(time - times[current]) / static_cast<float>(times[next] - times[current]) : 0.0f;
        bool sinc = available && cursor >= radius - 1 && cursor + radius < size;
        if (sinc) {
            const jlong span = times[(head + cursor + radius) % capacity] - times[(head + cursor - radius + 1) % capacity];
            sinc = span < (radius * 2 + 2) * 1000000000LL / rate;
        }
        const float* kernel = kernels + std::min(phases, static_cast<int>(std::lround(fraction * phases))) * taps;
        for (int c = 0; c < channels; ++c) {
            float value = 0;
            if (sinc) {
                for (int tap = 0; tap < taps; ++tap)
                    value += samples[static_cast<size_t>((head + cursor - radius + 1 + tap) % capacity) * channels + c] * kernel[tap];
            } else if (available) {
                const float a = samples[static_cast<size_t>(current) * channels + c];
                value = a + fraction * (samples[static_cast<size_t>(next) * channels + c] - a);
            }
            block[static_cast<size_t>(c) * frames + f] = value;
        }
    }
    env->ReleasePrimitiveArrayCritical(kernel_array, const_cast<float*>(kernels), JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(times_array, const_cast<jlong*>(times), JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(samples_array, const_cast<float*>(samples), JNI_ABORT);
    for (int c = 0; c < channels; ++c) {
        auto channel = static_cast<jfloatArray>(env->GetObjectArrayElement(output, offset + c));
        env->SetFloatArrayRegion(channel, 0, frames, block.data() + static_cast<size_t>(c) * frames);
        env->DeleteLocalRef(channel);
    }
    return static_cast<jlong>(cursor) << 32 | static_cast<uint32_t>(missing);
}

// One 3-pole Butterworth filter (first-order section, then transposed direct form II biquad) on one sample.
// k = {b0, b1, a1, b0, b1, b2, a1, a2}, s = {x1, y1, s1, s2}. Same operation order as PcmMixer.section().
static double section(double x, const double* k, double* s) {
    const double y = k[0] * x + k[1] * s[0] - k[2] * s[1];
    s[0] = x; s[1] = y;
    const double z = k[3] * y + s[2];
    s[2] = k[4] * y - k[6] * z + s[3];
    s[3] = k[5] * y - k[7] * z;
    return z;
}

// PcmMixer.mix(): per channel, optional high-/low-pass, then a stereo gain ramp across the block into the bus.
extern "C" JNIEXPORT void JNICALL
Java_net_sourceforge_opencamera_audio_PcmMixer_nativeAccumulate(
        JNIEnv* env, jclass, jobjectArray input, jint channels, jint frames, jfloatArray from_left_array,
        jfloatArray from_right_array, jfloatArray to_left_array, jfloatArray to_right_array, jintArray filters_array,
        jdoubleArray coefficients_array, jdoubleArray state_array, jfloatArray output,
        jfloatArray input_peaks_array, jfloatArray peaks_array) {
    std::vector<float> in(frames), bus(static_cast<size_t>(frames) * 2), from_left(channels), from_right(channels),
            to_left(channels), to_right(channels), input_peaks(channels), peaks(channels);
    std::vector<jint> filters(channels);
    std::vector<double> state(static_cast<size_t>(channels) * 8);
    double coefficients[16];
    env->GetFloatArrayRegion(from_left_array, 0, channels, from_left.data());
    env->GetFloatArrayRegion(from_right_array, 0, channels, from_right.data());
    env->GetFloatArrayRegion(to_left_array, 0, channels, to_left.data());
    env->GetFloatArrayRegion(to_right_array, 0, channels, to_right.data());
    env->GetIntArrayRegion(filters_array, 0, channels, filters.data());
    env->GetDoubleArrayRegion(coefficients_array, 0, 16, coefficients);
    env->GetDoubleArrayRegion(state_array, 0, channels * 8, state.data());
    for (int c = 0; c < channels; ++c) {
        auto channel = static_cast<jfloatArray>(env->GetObjectArrayElement(input, c));
        env->GetFloatArrayRegion(channel, 0, frames, in.data());
        env->DeleteLocalRef(channel);
        float left = from_left[c], right = from_right[c], input_peak = 0, peak = 0;
        const float dl = (to_left[c] - left) / frames, dr = (to_right[c] - right) / frames;
        double* filter_state = state.data() + static_cast<size_t>(c) * 8;
        for (int f = 0; f < frames; ++f) {
            float value = in[f];
            input_peak = std::max(input_peak, std::fabs(value)); // the source, before filters
            if (filters[c] != 0) {
                double x = value;
                if (filters[c] & 1) x = section(x, coefficients, filter_state);
                if (filters[c] & 2) x = section(x, coefficients + 8, filter_state + 4);
                value = static_cast<float>(x);
            }
            peak = std::max(peak, std::fabs(value));
            left += dl; right += dr;
            bus[f * 2] += value * left;
            bus[f * 2 + 1] += value * right;
        }
        input_peaks[c] = input_peak;
        peaks[c] = peak;
    }
    env->SetFloatArrayRegion(output, 0, frames * 2, bus.data());
    env->SetDoubleArrayRegion(state_array, 0, channels * 8, state.data());
    env->SetFloatArrayRegion(input_peaks_array, 0, channels, input_peaks.data());
    env->SetFloatArrayRegion(peaks_array, 0, channels, peaks.data());
}

static float clamp(float value, float low, float high) {
    return std::isnan(value) ? 0 : std::max(low, std::min(high, value));
}

// PcmMixer.mix(): peak limiter on the stereo bus. state = {gain in/out, left peak, right peak, min gain, clipped}.
extern "C" JNIEXPORT void JNICALL
Java_net_sourceforge_opencamera_audio_PcmMixer_nativeLimit(
        JNIEnv* env, jclass, jfloatArray output, jint frames, jboolean enabled, jfloat release, jfloatArray state_array) {
    std::vector<float> bus(static_cast<size_t>(frames) * 2);
    float state[5];
    env->GetFloatArrayRegion(output, 0, frames * 2, bus.data());
    env->GetFloatArrayRegion(state_array, 0, 5, state);
    float gain = state[0], left_peak = 0, right_peak = 0, min_gain = 1, clipped = 0;
    for (int f = 0; f < frames; ++f) {
        const int i = f * 2;
        const float peak = std::max(std::fabs(bus[i]), std::fabs(bus[i + 1]));
        if (peak >= 1) clipped = 1;
        const float wanted = enabled && peak > 0.98f ? 0.98f / peak : 1;
        gain = !enabled ? 1 : wanted < gain ? wanted : std::min(wanted, 1 - (1 - gain) * release);
        min_gain = std::min(min_gain, gain);
        bus[i] = clamp(bus[i] * gain, -1, 1);
        bus[i + 1] = clamp(bus[i + 1] * gain, -1, 1);
        left_peak = std::max(left_peak, std::fabs(bus[i])); right_peak = std::max(right_peak, std::fabs(bus[i + 1]));
    }
    state[0] = gain; state[1] = left_peak; state[2] = right_peak; state[3] = min_gain; state[4] = clipped;
    env->SetFloatArrayRegion(output, 0, frames * 2, bus.data());
    env->SetFloatArrayRegion(state_array, 0, 5, state);
}
