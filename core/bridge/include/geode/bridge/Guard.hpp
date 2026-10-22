#pragma once
// No C++ exception crosses the bridge (ADR-0005). Every bridge entry point runs its body through
// guarded(): whatever the body throws is caught here, turned into a GeodeStatus, and handed to
// `onCaught` (the JNI layer reports it to Kotlin as an engine failure) before the status returns.

#include <utility>

#include "geode/api/geode_status.h"

namespace geode::bridge {

// The status for the exception being handled. Call it only inside a catch block.
GeodeStatus statusOfCurrentException() noexcept;

// Runs `body`, which returns a GeodeStatus, and returns that status. If `body` throws, `onCaught`
// receives the status the exception maps to and that status is returned instead. `onCaught` must not
// throw; a throw from it ends the process, as any exception leaving a noexcept function does.
template <typename Body, typename OnCaught>
GeodeStatus guarded(Body&& body, OnCaught&& onCaught) noexcept {
    GeodeStatus caught = GEODE_STATUS_ENGINE_FAILURE;
    try {
        return std::forward<Body>(body)();
    } catch (...) {
        caught = statusOfCurrentException();
    }
    std::forward<OnCaught>(onCaught)(caught);
    return caught;
}

}  // namespace geode::bridge
