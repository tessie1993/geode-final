#include <jni.h>
#include <android/asset_manager_jni.h>

#include <cstring>
#include <optional>
#include <string>
#include <vector>

#include "geode/api/geode_api.h"
#include "jni_guard.hpp"
#include "jni_util.hpp"

namespace {

using geode::jni::FloatElements;
using geode::jni::statusCall;
using geode::jni::Utf8Chars;
using geode::jni::valueCall;

// The longest MilkDrop preset path handed back (PATH_MAX); a longer path cannot be opened anyway.
constexpr size_t kPresetPathCapacity = 4096;
// Floats per touch point: x and y.
constexpr jint kTouchFloatsPerPoint = 2;

geode_viz* vizOf(jlong handle) { return reinterpret_cast<geode_viz*>(handle); }

// A call on a viz handle with nothing to return: NULL_HANDLE for 0, otherwise whatever `action` returns.
template <typename Action>
jint vizCall(JNIEnv* env, const char* call, jlong handle, Action&& action) {
    return statusCall(env, call, [&]() -> GeodeStatus {
        if (!handle) return GEODE_STATUS_NULL_HANDLE;
        return action(vizOf(handle));
    });
}

jstring optionalString(JNIEnv* env, const std::optional<std::string>& text) {
    return text ? env->NewStringUTF(text->c_str()) : nullptr;
}

// Hands `pixels` (width*height ARGB ints) to `apply`, or a clear (null, 0x0) when the array is null or the
// size does not fit it, so a stale width or height can never make the engine read past the JVM's buffer.
template <typename Apply>
GeodeStatus withPixels(JNIEnv* env, jintArray pixels, jint width, jint height, Apply&& apply) {
    if (!pixels) {
        apply(nullptr, 0, 0);
        return GEODE_STATUS_OK;
    }
    jint* data = env->GetIntArrayElements(pixels, nullptr);
    if (!data) return GEODE_STATUS_OUT_OF_MEMORY;
    const jlong needed = static_cast<jlong>(width) * static_cast<jlong>(height);
    const bool fits = width > 0 && height > 0 && needed <= env->GetArrayLength(pixels);
    if (fits) {
        apply(reinterpret_cast<const uint32_t*>(data), width, height);
    } else {
        apply(nullptr, 0, 0);
    }
    env->ReleaseIntArrayElements(pixels, data, JNI_ABORT);
    return fits ? GEODE_STATUS_OK : GEODE_STATUS_INVALID_ARGUMENT;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizCreate(JNIEnv* env, jobject, jobject assetManager, jstring cacheDir) {
    return valueCall(env, "vizCreate", jlong{0}, [&] {
        Utf8Chars dir(env, cacheDir);
        AAssetManager* assets = assetManager ? AAssetManager_fromJava(env, assetManager) : nullptr;
        return reinterpret_cast<jlong>(geode_viz_create(assets, dir.get()));
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizDestroy(JNIEnv* env, jobject, jlong handle) {
    return vizCall(env, "vizDestroy", handle, [](geode_viz* v) {
        geode_viz_destroy(v);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jstring JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizParamNames(JNIEnv* env, jobject) {
    const std::string joined = valueCall(env, "vizParamNames", std::string(), [] {
        std::string names;
        for (int i = 0; i < geode_viz_param_count(); ++i) {
            if (i > 0) names += '\n';
            names += geode_viz_param_name(i);
        }
        return names;
    });
    return env->NewStringUTF(joined.c_str());
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetParams(JNIEnv* env, jobject, jlong handle, jfloatArray values) {
    return vizCall(env, "vizSetParams", handle, [&](geode_viz* v) {
        FloatElements data(env, values);
        if (!data.get() || data.length() != geode_viz_param_count()) return GEODE_STATUS_INVALID_ARGUMENT;
        geode_viz_set_params(v, data.get(), data.length());
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jboolean JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetParam(JNIEnv* env, jobject, jlong handle, jstring name, jfloat value) {
    return valueCall(env, "vizSetParam", jboolean{JNI_FALSE}, [&]() -> jboolean {
        Utf8Chars key(env, name);
        return geode_viz_set_param(vizOf(handle), key.get(), value) ? JNI_TRUE : JNI_FALSE;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetFeatures(JNIEnv* env, jobject, jlong handle, jfloatArray frame) {
    return vizCall(env, "vizSetFeatures", handle, [&](geode_viz* v) {
        FloatElements data(env, frame);
        if (!data.get() || data.length() < GEODE_FEATURE_FRAME_FLOATS) return GEODE_STATUS_INVALID_ARGUMENT;
        GeodeFeatureFrame features{};
        std::memcpy(&features, data.get(), sizeof(features));
        geode_viz_set_features(v, &features);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetReducedMotion(JNIEnv* env, jobject, jlong handle, jboolean on) {
    return vizCall(env, "vizSetReducedMotion", handle, [&](geode_viz* v) {
        geode_viz_set_reduced_motion(v, on == JNI_TRUE);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetLayer(JNIEnv* env, jobject, jlong handle, jstring sceneId, jfloat mix,
                                                     jint blendMode) {
    return vizCall(env, "vizSetLayer", handle, [&](geode_viz* v) {
        Utf8Chars id(env, sceneId);
        geode_viz_set_layer(v, id.get() ? id.get() : "", mix, blendMode);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetTransition(JNIEnv* env, jobject, jlong handle, jstring id, jlong durationMs) {
    return vizCall(env, "vizSetTransition", handle, [&](geode_viz* v) {
        Utf8Chars name(env, id);
        geode_viz_set_transition(v, name.get(), durationMs);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizBeginParamMorph(JNIEnv* env, jobject, jlong handle, jfloat seconds) {
    return vizCall(env, "vizBeginParamMorph", handle, [&](geode_viz* v) {
        geode_viz_begin_param_morph(v, seconds);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetTouch(JNIEnv* env, jobject, jlong handle, jfloatArray xy, jint points) {
    return vizCall(env, "vizSetTouch", handle, [&](geode_viz* v) {
        FloatElements data(env, xy);
        const jint available = data.get() ? data.length() / kTouchFloatsPerPoint : 0;
        geode_viz_set_touch(v, data.get(), points < available ? points : available);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetTouchSmear(JNIEnv* env, jobject, jlong handle, jboolean on, jfloat strength) {
    return vizCall(env, "vizSetTouchSmear", handle, [&](geode_viz* v) {
        geode_viz_set_touch_smear(v, on == JNI_TRUE ? 1 : 0, strength);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizQueueTouchStroke(JNIEnv* env, jobject, jlong handle, jfloat nx, jfloat ny, jfloat ndx,
                                                             jfloat ndy, jfloat dt, jfloat strength) {
    return vizCall(env, "vizQueueTouchStroke", handle, [&](geode_viz* v) {
        geode_viz_queue_touch_stroke(v, nx, ny, ndx, ndy, dt, strength);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizLoadMilkPreset(JNIEnv* env, jobject, jlong handle, jstring path) {
    return vizCall(env, "vizLoadMilkPreset", handle, [&](geode_viz* v) {
        Utf8Chars file(env, path);
        geode_viz_load_milk_preset(v, file.get());
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizReloadMilkPreset(JNIEnv* env, jobject, jlong handle) {
    return vizCall(env, "vizReloadMilkPreset", handle, [](geode_viz* v) {
        geode_viz_reload_milk_preset(v);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetMilkTextureDir(JNIEnv* env, jobject, jlong handle, jstring dir) {
    return vizCall(env, "vizSetMilkTextureDir", handle, [&](geode_viz* v) {
        Utf8Chars path(env, dir);
        geode_viz_set_milk_texture_dir(v, path.get());
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jstring JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizTakeMilkPresetLoaded(JNIEnv* env, jobject, jlong handle) {
    // The take is destructive, so it is called once: a length query first would consume the path and leave
    // the second call nothing.
    const std::optional<std::string> path =
        valueCall(env, "vizTakeMilkPresetLoaded", std::optional<std::string>(), [&]() -> std::optional<std::string> {
            std::vector<char> buffer(kPresetPathCapacity, '\0');
            const size_t length = geode_viz_take_milk_preset_loaded(vizOf(handle), buffer.data(), buffer.size());
            if (length == 0 || length >= buffer.size()) return std::nullopt;
            return std::string(buffer.data());
        });
    return optionalString(env, path);
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizPushPcm(JNIEnv* env, jobject, jlong handle, jfloatArray mono, jint count) {
    return vizCall(env, "vizPushPcm", handle, [&](geode_viz* v) {
        FloatElements data(env, mono);
        if (!data.get()) return GEODE_STATUS_INVALID_ARGUMENT;
        geode_viz_push_pcm(v, data.get(), count < data.length() ? count : data.length());
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetLfo(JNIEnv* env, jobject, jlong handle, jint slot, jfloatArray config) {
    return vizCall(env, "vizSetLfo", handle, [&](geode_viz* v) {
        FloatElements data(env, config);
        if (!data.get() || slot < 0 || slot >= GEODE_LFO_SLOTS || data.length() < GEODE_LFO_CONFIG_FLOATS) {
            return GEODE_STATUS_INVALID_ARGUMENT;
        }
        geode_viz_set_lfo(v, slot, data.get(), data.length());
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetAdsr(JNIEnv* env, jobject, jlong handle, jint slot, jfloatArray config) {
    return vizCall(env, "vizSetAdsr", handle, [&](geode_viz* v) {
        FloatElements data(env, config);
        if (!data.get() || slot < 0 || slot >= GEODE_ADSR_SLOTS || data.length() < GEODE_ADSR_CONFIG_FLOATS) {
            return GEODE_STATUS_INVALID_ARGUMENT;
        }
        geode_viz_set_adsr(v, slot, data.get(), data.length());
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetThermal(JNIEnv* env, jobject, jlong handle, jint status, jfloat headroom) {
    return vizCall(env, "vizSetThermal", handle, [&](geode_viz* v) {
        geode_viz_set_thermal(v, status, headroom);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetQualityCeiling(JNIEnv* env, jobject, jlong handle, jfloat ceiling) {
    return vizCall(env, "vizSetQualityCeiling", handle, [&](geode_viz* v) {
        geode_viz_set_quality_ceiling(v, ceiling);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetPacedFps(JNIEnv* env, jobject, jlong handle, jfloat fps) {
    return vizCall(env, "vizSetPacedFps", handle, [&](geode_viz* v) {
        geode_viz_set_paced_fps(v, fps);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetFrameLoad(JNIEnv* env, jobject, jlong handle, jint frames, jfloat p95Ms) {
    return vizCall(env, "vizSetFrameLoad", handle, [&](geode_viz* v) {
        geode_viz_set_frame_load(v, frames, p95Ms);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetOffscreen(JNIEnv* env, jobject, jlong handle, jboolean on) {
    return vizCall(env, "vizSetOffscreen", handle, [&](geode_viz* v) {
        geode_viz_set_offscreen(v, on == JNI_TRUE);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jboolean JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizKnows(JNIEnv* env, jobject, jlong handle, jstring sceneId) {
    return valueCall(env, "vizKnows", jboolean{JNI_FALSE}, [&]() -> jboolean {
        Utf8Chars id(env, sceneId);
        return geode_viz_knows(vizOf(handle), id.get()) ? JNI_TRUE : JNI_FALSE;
    });
}

JNIEXPORT jstring JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSceneIds(JNIEnv* env, jobject, jlong handle) {
    const std::string ids = valueCall(env, "vizSceneIds", std::string(), [&] {
        const size_t length = geode_viz_scene_ids(vizOf(handle), nullptr, 0);
        std::vector<char> buffer(length + 1, '\0');
        geode_viz_scene_ids(vizOf(handle), buffer.data(), buffer.size());
        return std::string(buffer.data());
    });
    return env->NewStringUTF(ids.c_str());
}

JNIEXPORT jstring JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizLastError(JNIEnv* env, jobject, jlong handle) {
    const std::string error = valueCall(env, "vizLastError", std::string(), [&] {
        const char* message = geode_viz_last_error(vizOf(handle));
        return std::string(message ? message : "");
    });
    return env->NewStringUTF(error.c_str());
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSurfaceCreated(JNIEnv* env, jobject, jlong handle) {
    return vizCall(env, "vizSurfaceCreated", handle, [](geode_viz* v) {
        geode_viz_surface_created(v);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSurfaceChanged(JNIEnv* env, jobject, jlong handle, jint width, jint height) {
    return vizCall(env, "vizSurfaceChanged", handle, [&](geode_viz* v) {
        geode_viz_surface_changed(v, width, height);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jboolean JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetScene(JNIEnv* env, jobject, jlong handle, jstring sceneId) {
    return valueCall(env, "vizSetScene", jboolean{JNI_FALSE}, [&]() -> jboolean {
        Utf8Chars id(env, sceneId);
        return geode_viz_set_scene(vizOf(handle), id.get()) ? JNI_TRUE : JNI_FALSE;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizWarmTransition(JNIEnv* env, jobject, jlong handle, jstring id) {
    return vizCall(env, "vizWarmTransition", handle, [&](geode_viz* v) {
        Utf8Chars name(env, id);
        geode_viz_warm_transition(v, name.get());
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizCut(JNIEnv* env, jobject, jlong handle) {
    return vizCall(env, "vizCut", handle, [](geode_viz* v) {
        geode_viz_cut(v);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizRender(JNIEnv* env, jobject, jlong handle, jdouble timeSeconds, jint targetFbo) {
    return vizCall(env, "vizRender", handle, [&](geode_viz* v) {
        geode_viz_render(v, timeSeconds, static_cast<uint32_t>(targetFbo));
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizReleaseScenes(JNIEnv* env, jobject, jlong handle) {
    return vizCall(env, "vizReleaseScenes", handle, [](geode_viz* v) {
        geode_viz_release_scenes(v);
        return GEODE_STATUS_OK;
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizAttachSnapshots(JNIEnv* env, jobject, jlong handle, jlong snapshots) {
    return vizCall(env, "vizAttachSnapshots", handle, [&](geode_viz* v) {
        return geode_viz_attach_snapshots(v, reinterpret_cast<geode_snapshots*>(snapshots));
    });
}

// pixels is a Java IntArray? (ARGB per Android's Bitmap.getPixels), so a null array is a valid "clear".
JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetOverlay(JNIEnv* env, jobject, jlong handle, jintArray pixels, jint width, jint height) {
    return vizCall(env, "vizSetOverlay", handle, [&](geode_viz* v) {
        return withPixels(env, pixels, width, height, [v](const uint32_t* data, int w, int h) {
            geode_viz_set_overlay_rgba(v, data, w, h);
        });
    });
}

JNIEXPORT jint JNICALL
Java_dev_geode_engine_bridge_GeodeNative_vizSetUnderlay(JNIEnv* env, jobject, jlong handle, jintArray pixels, jint width, jint height,
                                                        jint blend, jfloat amount) {
    return vizCall(env, "vizSetUnderlay", handle, [&](geode_viz* v) {
        return withPixels(env, pixels, width, height, [&](const uint32_t* data, int w, int h) {
            geode_viz_set_underlay_rgba(v, data, w, h, blend, amount);
        });
    });
}

}
