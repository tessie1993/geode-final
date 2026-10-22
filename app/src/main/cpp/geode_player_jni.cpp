#include <jni.h>

#include <array>
#include <string>

#include "geode/api/geode_api.h"
#include "jni_guard.hpp"

namespace {

using geode::jni::statusCall;
using geode::jni::valueCall;

// The longest player error message handed to Kotlin, terminator included.
constexpr size_t kErrorCapacity = 256;
// Floats per frame in the tap: the stereo mix.
constexpr jlong kTapFloatsPerFrame = 2;

geode_player* playerOf(jlong handle) { return reinterpret_cast<geode_player*>(handle); }

// A call on a player handle with nothing to return: NULL_HANDLE for 0, otherwise OK once `action` ran.
template <typename Action>
jint playerCall(JNIEnv* env, const char* call, jlong handle, Action&& action) {
    return statusCall(env, call, [&] {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        action(playerOf(handle));
        return GEODE_STATUS_OK;
    });
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerCreate(JNIEnv* env, jobject) {
    return valueCall(env, "playerCreate", jlong{0}, [] { return reinterpret_cast<jlong>(geode_player_create()); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerDestroy(JNIEnv* env, jobject, jlong handle) {
    return playerCall(env, "playerDestroy", handle, [](geode_player* p) { geode_player_destroy(p); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerOpen(JNIEnv* env, jobject, jlong handle, jint fd, jlong offset, jlong length,
                                                    jlong token) {
    return playerCall(env, "playerOpen", handle, [&](geode_player* p) { geode_player_open(p, fd, offset, length, token); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerSetNext(JNIEnv* env, jobject, jlong handle, jint fd, jlong offset,
                                                       jlong length, jlong token) {
    return playerCall(env, "playerSetNext", handle, [&](geode_player* p) { geode_player_set_next(p, fd, offset, length, token); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerPlay(JNIEnv* env, jobject, jlong handle) {
    return playerCall(env, "playerPlay", handle, [](geode_player* p) { geode_player_play(p); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerPause(JNIEnv* env, jobject, jlong handle) {
    return playerCall(env, "playerPause", handle, [](geode_player* p) { geode_player_pause(p); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerStop(JNIEnv* env, jobject, jlong handle) {
    return playerCall(env, "playerStop", handle, [](geode_player* p) { geode_player_stop(p); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerSeek(JNIEnv* env, jobject, jlong handle, jlong positionUs) {
    return playerCall(env, "playerSeek", handle, [&](geode_player* p) { geode_player_seek(p, positionUs); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerSetCrossfade(JNIEnv* env, jobject, jlong handle, jint durationMs, jint curve) {
    return playerCall(env, "playerSetCrossfade", handle, [&](geode_player* p) { geode_player_set_crossfade(p, durationMs, curve); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerSetDsp(JNIEnv* env, jobject, jlong handle, jlong dsp) {
    return playerCall(env, "playerSetDsp", handle,
                      [&](geode_player* p) { geode_player_set_dsp(p, reinterpret_cast<geode_dsp*>(dsp)); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerSetVolume(JNIEnv* env, jobject, jlong handle, jfloat volume) {
    return playerCall(env, "playerSetVolume", handle, [&](geode_player* p) { geode_player_set_volume(p, volume); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerState(JNIEnv* env, jobject, jlong handle) {
    // An engine that threw here is in no state to play: report it as the player's own error state.
    return valueCall(env, "playerState", jint{GEODE_PLAYER_ERROR}, [&] { return geode_player_state(playerOf(handle)); });
}

JNIEXPORT jboolean JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerPlayWhenReady(JNIEnv* env, jobject, jlong handle) {
    return valueCall(env, "playerPlayWhenReady", jboolean{JNI_FALSE}, [&]() -> jboolean {
        return geode_player_play_when_ready(playerOf(handle)) ? JNI_TRUE : JNI_FALSE;
    });
}

JNIEXPORT jlong JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerPositionUs(JNIEnv* env, jobject, jlong handle) {
    return valueCall(env, "playerPositionUs", jlong{0}, [&] { return static_cast<jlong>(geode_player_position_us(playerOf(handle))); });
}

JNIEXPORT jlong JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerDurationUs(JNIEnv* env, jobject, jlong handle) {
    return valueCall(env, "playerDurationUs", jlong{0}, [&] { return static_cast<jlong>(geode_player_duration_us(playerOf(handle))); });
}

JNIEXPORT jlong JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerCurrentToken(JNIEnv* env, jobject, jlong handle) {
    return valueCall(env, "playerCurrentToken", jlong{-1},
                     [&] { return static_cast<jlong>(geode_player_current_token(playerOf(handle))); });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerOutputSampleRate(JNIEnv* env, jobject, jlong handle) {
    return valueCall(env, "playerOutputSampleRate", jint{0}, [&] { return geode_player_output_sample_rate(playerOf(handle)); });
}

JNIEXPORT jstring JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerLastError(JNIEnv* env, jobject, jlong handle) {
    const std::string message = valueCall(env, "playerLastError", std::string(), [&] {
        std::array<char, kErrorCapacity> buffer{};
        geode_player_last_error(playerOf(handle), buffer.data(), buffer.size());
        return std::string(buffer.data());
    });
    return env->NewStringUTF(message.c_str());
}

// The buffer must be direct: the analysis thread reads straight out of the mixer's ring.
JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_playerReadTap(JNIEnv* env, jobject, jlong handle, jobject buffer, jint frames) {
    return valueCall(env, "playerReadTap", jint{0}, [&]() -> jint {
        if (!buffer || frames <= 0) return 0;
        auto* out = static_cast<float*>(env->GetDirectBufferAddress(buffer));
        if (!out) return 0;
        const jlong capacityFrames = env->GetDirectBufferCapacity(buffer) / static_cast<jlong>(kTapFloatsPerFrame * sizeof(float));
        const size_t wanted = static_cast<size_t>(frames < capacityFrames ? frames : capacityFrames);
        return static_cast<jint>(geode_player_read_tap(playerOf(handle), out, wanted));
    });
}

}  // extern "C"
