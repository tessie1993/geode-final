#pragma once
// What a geode_snapshots handle is, for the C ABI files that publish to it or read from it.

#include "geode/bridge/SnapshotExchange.hpp"

struct geode_snapshots {
    geode::bridge::SnapshotExchange exchange;
};
