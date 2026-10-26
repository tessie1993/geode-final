#pragma once
// What a geode_feed handle is, for the C ABI files that write into it, read from it or attach to it.

#include <memory>

#include "Feed.hpp"

// Shared, so a Visual attached to the feed keeps it alive until it detaches, whatever order the
// handles are destroyed in.
struct geode_feed {
    std::shared_ptr<geode::api::Feed> feed = std::make_shared<geode::api::Feed>();
};
