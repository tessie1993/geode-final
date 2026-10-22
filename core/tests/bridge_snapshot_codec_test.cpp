#include "geode/bridge/CustomizationSnapshot.hpp"
#include "geode/util/Sha256.hpp"

#include <gtest/gtest.h>

#include <cstring>
#include <limits>
#include <vector>

using geode::bridge::CustomizationSnapshot;
using geode::bridge::decodeSnapshot;
using geode::bridge::encodeSnapshot;
using geode::bridge::kSnapshotHeaderBytes;
using geode::bridge::kSnapshotWireBytes;

namespace {

// Byte offsets inside the wire format (tools/bridge_schema/generate.py).
constexpr size_t kMagicAt = 0;
constexpr size_t kVersionAt = 4;
constexpr size_t kReservedAt = 6;
constexpr size_t kPayloadLengthAt = 8;
constexpr size_t kRevisionAt = kSnapshotHeaderBytes;
constexpr size_t kReducedMotionAt = kRevisionAt + sizeof(uint32_t);
constexpr size_t kParamsAt = kReducedMotionAt + 1;
constexpr size_t kLfoAt = kParamsAt + geode::bridge::kSnapshotParamsCount * sizeof(float);
constexpr size_t kAdsrAt = kLfoAt + geode::bridge::kSnapshotLfoCount * sizeof(float);
constexpr size_t kSoundAt = kAdsrAt + geode::bridge::kSnapshotAdsrCount * sizeof(float);
constexpr size_t kSoundFields = 4;

// The same snapshot CustomizationSnapshotTest.kt encodes; both sides must produce this digest. It changes
// whenever the schema's layout does (an array count included): recompute it then, on both sides.
constexpr const char* kFixtureDigest = "283b73e6f1ee6bdb852e39f44b13ed9bdba8c07ad82075b2fe515a9a3eacbb1b";

CustomizationSnapshot fixture() {
    CustomizationSnapshot s;
    s.revision = 7;
    s.reducedMotion = true;
    for (size_t i = 0; i < s.params.size(); ++i) s.params[i] = static_cast<float>(i) * 0.5f;
    for (size_t i = 0; i < s.lfo.size(); ++i) s.lfo[i] = static_cast<float>(i) * 0.25f;
    // Negated as an integer, so element 0 is +0.0f, as Kotlin's `-it * 0.125f` makes it.
    for (size_t i = 0; i < s.adsr.size(); ++i) s.adsr[i] = static_cast<float>(-static_cast<int>(i)) * 0.125f;
    s.soundSensitivity = 1.5f;
    s.soundRefractoryMs = 80.0f;
    s.soundAttackSeconds = 0.25f;
    s.soundReleaseSeconds = 0.5f;
    return s;
}

std::vector<uint8_t> encoded(const CustomizationSnapshot& s) {
    std::vector<uint8_t> wire(kSnapshotWireBytes);
    EXPECT_EQ(encodeSnapshot(s, wire), kSnapshotWireBytes);
    return wire;
}

void putFloat(std::vector<uint8_t>& wire, size_t at, float value) {
    std::memcpy(wire.data() + at, &value, sizeof(value));
}

// A decode that must fail leaves its output exactly as it was.
GeodeStatus decodeRejected(const std::vector<uint8_t>& wire) {
    CustomizationSnapshot out;
    out.revision = 99;
    const GeodeStatus status = decodeSnapshot(wire, out);
    EXPECT_EQ(out, CustomizationSnapshot{.revision = 99});
    return status;
}

}  // namespace

TEST(SnapshotCodec, RoundTripsEveryField) {
    const CustomizationSnapshot original = fixture();
    CustomizationSnapshot decoded;
    ASSERT_EQ(decodeSnapshot(encoded(original), decoded), GEODE_STATUS_OK);
    EXPECT_EQ(decoded, original);
}

TEST(SnapshotCodec, MatchesTheKotlinEncoderByteForByte) {
    const std::vector<uint8_t> wire = encoded(fixture());
    geode::viz::Sha256 sha;
    sha.update(wire.data(), wire.size());
    EXPECT_EQ(geode::viz::Sha256::hex(sha.digest(), 32), kFixtureDigest);
}

TEST(SnapshotCodec, WritesTheHeaderLittleEndian) {
    const std::vector<uint8_t> wire = encoded(fixture());
    EXPECT_EQ(wire[kMagicAt], 'G');
    EXPECT_EQ(wire[kMagicAt + 1], 'C');
    EXPECT_EQ(wire[kMagicAt + 2], 'S');
    EXPECT_EQ(wire[kMagicAt + 3], '1');
    EXPECT_EQ(wire[kVersionAt], geode::bridge::kSnapshotVersion);
    EXPECT_EQ(wire[kVersionAt + 1], 0);
    EXPECT_EQ(wire[kReservedAt], 0);
    EXPECT_EQ(wire[kPayloadLengthAt], geode::bridge::kSnapshotPayloadBytes & 0xFF);
    EXPECT_EQ(wire[kPayloadLengthAt + 1], geode::bridge::kSnapshotPayloadBytes >> 8);
    EXPECT_EQ(wire[kRevisionAt], 7);
    EXPECT_EQ(wire[kReducedMotionAt], 1);
}

TEST(SnapshotCodec, WritesAFalseFlagAsZero) {
    CustomizationSnapshot s = fixture();
    s.reducedMotion = false;
    const std::vector<uint8_t> wire = encoded(s);
    EXPECT_EQ(wire[kReducedMotionAt], 0);
    CustomizationSnapshot decoded = fixture();
    ASSERT_EQ(decodeSnapshot(wire, decoded), GEODE_STATUS_OK);
    EXPECT_FALSE(decoded.reducedMotion);
}

TEST(SnapshotCodec, EncodeRefusesABufferTooSmall) {
    std::vector<uint8_t> wire(kSnapshotWireBytes - 1, 0xAB);
    EXPECT_EQ(encodeSnapshot(fixture(), wire), 0u);
    EXPECT_EQ(wire.front(), 0xAB);
}

TEST(SnapshotCodec, EncodeFillsOnlyTheSnapshotOfALargerBuffer) {
    std::vector<uint8_t> wire(kSnapshotWireBytes + 1, 0xAB);
    EXPECT_EQ(encodeSnapshot(fixture(), wire), kSnapshotWireBytes);
    EXPECT_EQ(wire.back(), 0xAB);
}

TEST(SnapshotCodec, RejectsBytesShorterThanTheHeader) {
    const std::vector<uint8_t> full = encoded(fixture());
    EXPECT_EQ(decodeRejected({}), GEODE_STATUS_MALFORMED_SNAPSHOT);
    EXPECT_EQ(decodeRejected(std::vector<uint8_t>(full.begin(), full.begin() + kSnapshotHeaderBytes - 1)),
              GEODE_STATUS_MALFORMED_SNAPSHOT);
}

TEST(SnapshotCodec, RejectsAWrongMagic) {
    std::vector<uint8_t> wire = encoded(fixture());
    wire[kMagicAt + 3] = '2';
    EXPECT_EQ(decodeRejected(wire), GEODE_STATUS_MALFORMED_SNAPSHOT);
}

TEST(SnapshotCodec, ReportsAnotherVersionAsUnsupported) {
    std::vector<uint8_t> wire = encoded(fixture());
    wire[kVersionAt] = static_cast<uint8_t>(geode::bridge::kSnapshotVersion + 1);
    EXPECT_EQ(decodeRejected(wire), GEODE_STATUS_UNSUPPORTED_VERSION);
}

TEST(SnapshotCodec, ChecksTheVersionBeforeTheLength) {
    // A newer version may well be longer; it must still read as a version problem, not as garbage.
    std::vector<uint8_t> wire = encoded(fixture());
    wire[kVersionAt] = static_cast<uint8_t>(geode::bridge::kSnapshotVersion + 1);
    wire.push_back(0);
    EXPECT_EQ(decodeRejected(wire), GEODE_STATUS_UNSUPPORTED_VERSION);
}

TEST(SnapshotCodec, RejectsANonZeroReservedField) {
    std::vector<uint8_t> wire = encoded(fixture());
    wire[kReservedAt + 1] = 1;
    EXPECT_EQ(decodeRejected(wire), GEODE_STATUS_MALFORMED_SNAPSHOT);
}

TEST(SnapshotCodec, RejectsAPayloadLengthThatIsNotTheSchemas) {
    std::vector<uint8_t> wire = encoded(fixture());
    wire[kPayloadLengthAt] = static_cast<uint8_t>(wire[kPayloadLengthAt] - 1);
    EXPECT_EQ(decodeRejected(wire), GEODE_STATUS_MALFORMED_SNAPSHOT);
}

TEST(SnapshotCodec, RejectsATruncatedPayload) {
    std::vector<uint8_t> wire = encoded(fixture());
    wire.pop_back();
    EXPECT_EQ(decodeRejected(wire), GEODE_STATUS_MALFORMED_SNAPSHOT);
}

TEST(SnapshotCodec, RejectsTrailingBytes) {
    std::vector<uint8_t> wire = encoded(fixture());
    wire.push_back(0);
    EXPECT_EQ(decodeRejected(wire), GEODE_STATUS_MALFORMED_SNAPSHOT);
}

TEST(SnapshotCodec, RejectsAFlagThatIsNeitherZeroNorOne) {
    std::vector<uint8_t> wire = encoded(fixture());
    wire[kReducedMotionAt] = 2;
    EXPECT_EQ(decodeRejected(wire), GEODE_STATUS_MALFORMED_SNAPSHOT);
}

TEST(SnapshotCodec, RejectsANonFiniteValueInEveryArray) {
    const float nan = std::numeric_limits<float>::quiet_NaN();
    for (const size_t at : {kParamsAt, kLfoAt + sizeof(float), kAdsrAt + 2 * sizeof(float)}) {
        std::vector<uint8_t> wire = encoded(fixture());
        putFloat(wire, at, nan);
        EXPECT_EQ(decodeRejected(wire), GEODE_STATUS_INVALID_ARGUMENT) << "at byte " << at;
    }
}

TEST(SnapshotCodec, RejectsTheLastElementOfAnArrayWhenNotFinite) {
    std::vector<uint8_t> wire = encoded(fixture());
    putFloat(wire, kLfoAt - sizeof(float), std::numeric_limits<float>::infinity());
    EXPECT_EQ(decodeRejected(wire), GEODE_STATUS_INVALID_ARGUMENT);
}

TEST(SnapshotCodec, RejectsAnInfiniteSoundSetting) {
    for (size_t field = 0; field < kSoundFields; ++field) {
        std::vector<uint8_t> wire = encoded(fixture());
        putFloat(wire, kSoundAt + field * sizeof(float), -std::numeric_limits<float>::infinity());
        EXPECT_EQ(decodeRejected(wire), GEODE_STATUS_INVALID_ARGUMENT) << "sound field " << field;
    }
}

TEST(SnapshotCodec, AcceptsNegativeAndZeroValues) {
    CustomizationSnapshot s = fixture();
    s.params.fill(-0.0f);
    s.soundSensitivity = -3.0f;
    CustomizationSnapshot decoded;
    ASSERT_EQ(decodeSnapshot(encoded(s), decoded), GEODE_STATUS_OK);
    EXPECT_EQ(decoded.soundSensitivity, -3.0f);
}

TEST(SnapshotCodec, CarriesTheLargestRevision) {
    CustomizationSnapshot s = fixture();
    s.revision = std::numeric_limits<uint32_t>::max();
    CustomizationSnapshot decoded;
    ASSERT_EQ(decodeSnapshot(encoded(s), decoded), GEODE_STATUS_OK);
    EXPECT_EQ(decoded.revision, std::numeric_limits<uint32_t>::max());
}
