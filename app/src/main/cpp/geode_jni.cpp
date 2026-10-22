#include <jni.h>

#include <array>
#include <string>
#include <vector>

#include "geode/api/geode_api.h"
#include "jni_guard.hpp"
#include "jni_util.hpp"

namespace {

using geode::jni::FloatElements;
using geode::jni::statusCall;
using geode::jni::valueCall;

// Floats per frame geode_pulse_replay writes: beat, strength, transient, phase, confidence, energy.
constexpr jsize kPulseFloats = 6;
// Impulses geode_drums_step writes: kick, snare, hat.
constexpr jsize kDrumImpulses = 3;
// The longest key name geode_analysis_key writes, terminator included.
constexpr size_t kKeyCapacity = 32;

geode_analysis* analysisOf(jlong handle) { return reinterpret_cast<geode_analysis*>(handle); }
geode_drums* drumsOf(jlong handle) { return reinterpret_cast<geode_drums*>(handle); }
geode_dsp* dspOf(jlong handle) { return reinterpret_cast<geode_dsp*>(handle); }
geode_snapshots* snapshotsOf(jlong handle) { return reinterpret_cast<geode_snapshots*>(handle); }

bool writeFrame(JNIEnv* env, const GeodeFeatureFrame& frame, jfloatArray out) {
    if (!out || env->GetArrayLength(out) < GEODE_FEATURE_FRAME_FLOATS) return false;
    env->SetFloatArrayRegion(out, 0, GEODE_FEATURE_FRAME_FLOATS, reinterpret_cast<const jfloat*>(&frame));
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_geode_engine_bridge_GeodeNative_version(JNIEnv* env, jobject) {
    const std::string version = valueCall(env, "version", std::string(), [] { return std::string(geode_version()); });
    return env->NewStringUTF(version.c_str());
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_featureFrameFloats(JNIEnv*, jobject) {
    return GEODE_FEATURE_FRAME_FLOATS;
}

JNIEXPORT jlong JNICALL
Java_dev_geode_engine_bridge_GeodeNative_analysisCreate(JNIEnv* env, jobject, jint sampleRate, jint fftSize, jfloat hopRateHz) {
    return valueCall(env, "analysisCreate", jlong{0}, [&] {
        return reinterpret_cast<jlong>(geode_analysis_create(sampleRate, fftSize, hopRateHz));
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_analysisDestroy(JNIEnv* env, jobject, jlong handle) {
    return statusCall(env, "analysisDestroy", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_analysis_destroy(analysisOf(handle));
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_analysisSetSampleRate(JNIEnv* env, jobject, jlong handle, jint sampleRate) {
    return statusCall(env, "analysisSetSampleRate", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_analysis_set_sample_rate(analysisOf(handle), sampleRate);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_analysisSetTuning(JNIEnv* env, jobject, jlong handle, jfloat sensitivity,
                                                           jfloat refractoryMs, jfloat attackSeconds, jfloat releaseSeconds) {
    return statusCall(env, "analysisSetTuning", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_analysis_set_tuning(analysisOf(handle), sensitivity, refractoryMs, attackSeconds, releaseSeconds);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_analysisReset(JNIEnv* env, jobject, jlong handle) {
    return statusCall(env, "analysisReset", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_analysis_reset(analysisOf(handle));
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_analysisAnalyze(JNIEnv* env, jobject, jlong handle, jfloatArray mid,
                                                         jfloatArray side, jfloat dtSeconds, jfloatArray out) {
    return statusCall(env, "analysisAnalyze", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        FloatElements midData(env, mid);
        FloatElements sideData(env, side);
        if (!midData.get()) return GEODE_STATUS_INVALID_ARGUMENT;
        const jsize frames = sideData.get() && sideData.length() < midData.length() ? sideData.length() : midData.length();
        GeodeFeatureFrame frame{};
        geode_analysis_analyze(analysisOf(handle), midData.get(), sideData.get(), static_cast<size_t>(frames), dtSeconds, &frame);
        return writeFrame(env, frame, out) ? GEODE_STATUS_OK : GEODE_STATUS_INVALID_ARGUMENT;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_analysisPush(JNIEnv* env, jobject, jlong handle, jfloatArray interleaved,
                                                      jint frames, jint channels) {
    return statusCall(env, "analysisPush", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        FloatElements data(env, interleaved);
        if (!data.get() || frames <= 0 || channels <= 0) return GEODE_STATUS_INVALID_ARGUMENT;
        const jint available = data.length() / channels;
        const jint count = frames < available ? frames : available;
        geode_analysis_push(analysisOf(handle), data.get(), static_cast<size_t>(count), channels);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jboolean JNICALL
Java_dev_geode_engine_bridge_GeodeNative_analysisPull(JNIEnv* env, jobject, jlong handle, jfloatArray out) {
    return valueCall(env, "analysisPull", jboolean{JNI_FALSE}, [&]() -> jboolean {
        GeodeFeatureFrame frame{};
        if (!geode_analysis_pull(analysisOf(handle), &frame)) return JNI_FALSE;
        writeFrame(env, frame, out);
        return JNI_TRUE;
    });
}

JNIEXPORT jstring JNICALL
Java_dev_geode_engine_bridge_GeodeNative_analysisKey(JNIEnv* env, jobject, jlong handle) {
    const std::string key = valueCall(env, "analysisKey", std::string(), [&] {
        std::array<char, kKeyCapacity> buffer{};
        geode_analysis_key(analysisOf(handle), buffer.data(), buffer.size());
        return std::string(buffer.data());
    });
    return env->NewStringUTF(key.c_str());
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_analysisAttachSnapshots(JNIEnv* env, jobject, jlong handle, jlong snapshots) {
    return statusCall(env, "analysisAttachSnapshots", [&] {
        return geode_analysis_attach_snapshots(analysisOf(handle), snapshotsOf(snapshots));
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_pulseReplay(JNIEnv* env, jobject, jfloatArray flux, jfloatArray rms,
                                                     jfloat hopRateHz, jfloat sensitivity, jfloat refractoryMs, jfloatArray out) {
    return statusCall(env, "pulseReplay", [&] {
        FloatElements fluxData(env, flux);
        FloatElements rmsData(env, rms);
        if (!fluxData.get() || !out) return GEODE_STATUS_INVALID_ARGUMENT;
        const jsize count = fluxData.length();
        if (env->GetArrayLength(out) < count * kPulseFloats) return GEODE_STATUS_INVALID_ARGUMENT;
        std::vector<float> frames(static_cast<size_t>(count) * kPulseFloats);
        geode_pulse_replay(fluxData.get(), static_cast<size_t>(count), rmsData.get(), static_cast<size_t>(rmsData.length()),
                           hopRateHz, sensitivity, refractoryMs, frames.data());
        env->SetFloatArrayRegion(out, 0, count * kPulseFloats, frames.data());
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jlong JNICALL
Java_dev_geode_engine_bridge_GeodeNative_drumsCreate(JNIEnv* env, jobject, jint bandCount, jfloat hopRateHz, jint sampleRate) {
    return valueCall(env, "drumsCreate", jlong{0}, [&] {
        return reinterpret_cast<jlong>(geode_drums_create(bandCount, hopRateHz, sampleRate));
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_drumsDestroy(JNIEnv* env, jobject, jlong handle) {
    return statusCall(env, "drumsDestroy", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_drums_destroy(drumsOf(handle));
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_drumsStep(JNIEnv* env, jobject, jlong handle, jfloatArray bands, jfloatArray out) {
    return statusCall(env, "drumsStep", [&] {
        geode_drums* drums = drumsOf(handle);
        if (!drums) return GEODE_STATUS_NULL_HANDLE;
        FloatElements data(env, bands);
        if (!data.get() || !out || env->GetArrayLength(out) < kDrumImpulses) return GEODE_STATUS_INVALID_ARGUMENT;
        // DrumChannels::step reads bands[0..bandCount); the C API has no length for `bands`, so clamp against
        // the count this handle was created with, the same way analysisPush clamps against `interleaved`.
        if (data.length() < geode_drums_band_count(drums)) return GEODE_STATUS_INVALID_ARGUMENT;
        std::array<float, kDrumImpulses> impulses{};
        geode_drums_step(drums, data.get(), impulses.data());
        env->SetFloatArrayRegion(out, 0, kDrumImpulses, impulses.data());
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jlong JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspCreate(JNIEnv* env, jobject, jint sampleRate, jint channels) {
    return valueCall(env, "dspCreate", jlong{0}, [&] {
        return reinterpret_cast<jlong>(geode_dsp_create(sampleRate, channels));
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspDestroy(JNIEnv* env, jobject, jlong handle) {
    return statusCall(env, "dspDestroy", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_dsp_destroy(dspOf(handle));
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspBandCount(JNIEnv*, jobject) {
    return geode_dsp_band_count();
}

JNIEXPORT jfloat JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspBandCenterHz(JNIEnv* env, jobject, jint band) {
    return valueCall(env, "dspBandCenterHz", jfloat{0.0f}, [&] { return geode_dsp_band_center_hz(band); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspSetEnabled(JNIEnv* env, jobject, jlong handle, jboolean enabled) {
    return statusCall(env, "dspSetEnabled", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_dsp_set_enabled(dspOf(handle), enabled == JNI_TRUE);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspSetBand(JNIEnv* env, jobject, jlong handle, jint band, jint millibels) {
    return statusCall(env, "dspSetBand", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_dsp_set_band(dspOf(handle), band, millibels);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspBand(JNIEnv* env, jobject, jlong handle, jint band) {
    return valueCall(env, "dspBand", jint{0}, [&] { return geode_dsp_band(dspOf(handle), band); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspSetBassBoost(JNIEnv* env, jobject, jlong handle, jint permille) {
    return statusCall(env, "dspSetBassBoost", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_dsp_set_bass_boost(dspOf(handle), permille);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspSetLoudnessMb(JNIEnv* env, jobject, jlong handle, jint millibels) {
    return statusCall(env, "dspSetLoudnessMb", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_dsp_set_loudness_mb(dspOf(handle), millibels);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspSetGainDb(JNIEnv* env, jobject, jlong handle, jfloat db) {
    return statusCall(env, "dspSetGainDb", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_dsp_set_gain_db(dspOf(handle), db);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspSetCrossfeed(JNIEnv* env, jobject, jlong handle, jboolean enabled) {
    return statusCall(env, "dspSetCrossfeed", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_dsp_set_crossfeed(dspOf(handle), enabled == JNI_TRUE);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspSetLimiter(JNIEnv* env, jobject, jlong handle, jboolean enabled) {
    return statusCall(env, "dspSetLimiter", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_dsp_set_limiter(dspOf(handle), enabled == JNI_TRUE);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspReset(JNIEnv* env, jobject, jlong handle) {
    return statusCall(env, "dspReset", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_dsp_reset(dspOf(handle));
        return GEODE_STATUS_OK;
    });
}

// The buffer must be direct: the audio thread must not copy or allocate here.
JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspProcess(JNIEnv* env, jobject, jlong handle, jobject buffer, jint frames) {
    return statusCall(env, "dspProcess", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        if (!buffer) return GEODE_STATUS_INVALID_ARGUMENT;
        auto* samples = static_cast<float*>(env->GetDirectBufferAddress(buffer));
        if (!samples || frames <= 0) return GEODE_STATUS_INVALID_ARGUMENT;
        geode_dsp_process(dspOf(handle), samples, static_cast<size_t>(frames));
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspSetSampleRate(JNIEnv* env, jobject, jlong handle, jint sampleRate) {
    return statusCall(env, "dspSetSampleRate", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_dsp_set_sample_rate(dspOf(handle), sampleRate);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_dspSampleRate(JNIEnv* env, jobject, jlong handle) {
    return valueCall(env, "dspSampleRate", jint{0}, [&] { return geode_dsp_sample_rate(dspOf(handle)); });
}

JNIEXPORT jlong JNICALL
Java_dev_geode_engine_bridge_GeodeNative_snapshotsCreate(JNIEnv* env, jobject) {
    return valueCall(env, "snapshotsCreate", jlong{0}, [] { return reinterpret_cast<jlong>(geode_snapshots_create()); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_snapshotsDestroy(JNIEnv* env, jobject, jlong handle) {
    return statusCall(env, "snapshotsDestroy", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        geode_snapshots_destroy(snapshotsOf(handle));
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_snapshotsPublish(JNIEnv* env, jobject, jlong handle, jbyteArray bytes) {
    return statusCall(env, "snapshotsPublish", [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        if (!bytes) return GEODE_STATUS_INVALID_ARGUMENT;
        const jsize length = env->GetArrayLength(bytes);
        // Copied out of the Java array first so the decode never runs with the array pinned.
        std::vector<uint8_t> wire(static_cast<size_t>(length));
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(wire.data()));
        return geode_snapshots_publish(snapshotsOf(handle), wire.data(), wire.size());
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_snapshotsRevision(JNIEnv* env, jobject, jlong handle) {
    return valueCall(env, "snapshotsRevision", jint{0}, [&] {
        return static_cast<jint>(geode_snapshots_revision(snapshotsOf(handle)));
    });
}

}
