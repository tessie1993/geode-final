#include "geode/util/Sha256.hpp"

#include <gtest/gtest.h>

#include <string>

using geode::viz::Sha256;

namespace {

std::string hashOf(const std::string& text) {
    Sha256 sha;
    sha.update(text.data(), text.size());
    return Sha256::hex(sha.digest(), 32);
}

}  // namespace

// FIPS 180-2 test vectors.
TEST(Sha256, EmptyInput) {
    EXPECT_EQ(hashOf(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
}

TEST(Sha256, Abc) {
    EXPECT_EQ(hashOf("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
}

TEST(Sha256, TwoBlockMessage) {
    EXPECT_EQ(hashOf("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
              "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
}

TEST(Sha256, ChunkedUpdatesMatchOneUpdate) {
    const std::string text(1000, 'g');
    Sha256 sha;
    for (size_t i = 0; i < text.size(); i += 7) {
        sha.update(text.data() + i, std::min<size_t>(7, text.size() - i));
    }
    EXPECT_EQ(Sha256::hex(sha.digest(), 32), hashOf(text));
}

TEST(Sha256, HexTruncatesToRequestedBytes) {
    Sha256 sha;
    EXPECT_EQ(Sha256::hex(sha.digest(), 4), "e3b0c442");
}
