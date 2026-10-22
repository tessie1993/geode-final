#include "geode/bridge/Guard.hpp"

#include <new>
#include <stdexcept>

namespace geode::bridge {

GeodeStatus statusOfCurrentException() noexcept {
    try {
        throw;
    } catch (const std::bad_alloc&) {
        return GEODE_STATUS_OUT_OF_MEMORY;
    } catch (const std::invalid_argument&) {
        return GEODE_STATUS_INVALID_ARGUMENT;
    } catch (const std::out_of_range&) {
        return GEODE_STATUS_INVALID_ARGUMENT;
    } catch (...) {
        return GEODE_STATUS_ENGINE_FAILURE;
    }
}

}  // namespace geode::bridge
