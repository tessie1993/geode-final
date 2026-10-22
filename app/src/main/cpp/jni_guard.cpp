#include "jni_guard.hpp"

namespace {

// Kotlin's receiver for failures caught at the bridge: dev.geode.engine.bridge.NativeFailures.onCaught.
constexpr const char* kFailuresClass = "dev/geode/engine/bridge/NativeFailures";
constexpr const char* kOnCaught = "onCaught";
constexpr const char* kOnCaughtSignature = "(ILjava/lang/String;)V";

// Resolved once in JNI_OnLoad, where the app's class loader is in reach; any thread can use them after.
jclass gFailures = nullptr;
jmethodID gOnCaught = nullptr;

}  // namespace

namespace geode::jni {

void reportCaught(JNIEnv* env, GeodeStatus status, const char* call) noexcept {
    if (!env || !gFailures || !gOnCaught || env->ExceptionCheck()) return;
    jstring name = env->NewStringUTF(call ? call : "");
    if (!name) {
        env->ExceptionClear();
        return;
    }
    env->CallStaticVoidMethod(gFailures, gOnCaught, static_cast<jint>(status), name);
    // A listener that threw must not turn a caught engine failure into a Java exception at the call site.
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(name);
}

}  // namespace geode::jni

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = env->FindClass(kFailuresClass);
    if (!local) {
        // Without the receiver the bridge still never lets an exception through; it just cannot report.
        env->ExceptionClear();
        return JNI_VERSION_1_6;
    }
    gOnCaught = env->GetStaticMethodID(local, kOnCaught, kOnCaughtSignature);
    if (gOnCaught) {
        gFailures = static_cast<jclass>(env->NewGlobalRef(local));
    } else {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(local);
    return JNI_VERSION_1_6;
}
