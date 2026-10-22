#pragma once
// Every JNI entry point runs through one of these (ADR-0005, "No exceptions across the bridge").
//
// - statusCall: a call with nothing else to return gives Kotlin its GeodeStatus.
// - valueCall:  a call that returns a value gives Kotlin that value, or `fallback` when the engine threw.
//
// Either way a C++ exception never reaches the JVM: it is caught at the bridge, mapped to a status, and
// reported to Kotlin's NativeFailures, which turns it into a named failure on the app-wide error channel
// (and restarts the audio when the audio engine was the one that failed).

#include <jni.h>

#include <utility>

#include "geode/api/geode_status.h"
#include "geode/bridge/Guard.hpp"

namespace geode::jni {

// Tells Kotlin that `call` threw and what it was caught as. Never throws; does nothing while a Java
// exception is pending or before JNI_OnLoad found NativeFailures.
void reportCaught(JNIEnv* env, GeodeStatus status, const char* call) noexcept;

template <typename Body>
jint statusCall(JNIEnv* env, const char* call, Body&& body) noexcept {
    return static_cast<jint>(geode::bridge::guarded(std::forward<Body>(body), [env, call](GeodeStatus caught) {
        reportCaught(env, caught, call);
    }));
}

// T must copy without throwing (a JNI scalar, a local reference, an empty std::string or std::optional).
template <typename T, typename Body>
T valueCall(JNIEnv* env, const char* call, T fallback, Body&& body) noexcept {
    T result = fallback;
    geode::bridge::guarded(
        [&]() -> GeodeStatus {
            result = body();
            return GEODE_STATUS_OK;
        },
        [&](GeodeStatus caught) {
            result = fallback;
            reportCaught(env, caught, call);
        });
    return result;
}

}  // namespace geode::jni
