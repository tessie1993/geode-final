#include "util/Json.hpp"

#include <gtest/gtest.h>

namespace json = geode::json;

TEST(Json, ParsesNestedDocument) {
    const auto v = json::parse(R"({"name":"Kyanite","gain":0.75,"on":true,"tags":[1,null,"x"]})");
    ASSERT_TRUE(v.has_value());
    ASSERT_EQ(v->kind(), json::Value::Kind::Object);
    EXPECT_EQ(v->optString("name"), "Kyanite");
    ASSERT_NE(v->get("gain"), nullptr);
    EXPECT_DOUBLE_EQ(v->get("gain")->number(), 0.75);
    EXPECT_TRUE(v->get("on")->boolValue());
    const auto& tags = v->get("tags")->array();
    ASSERT_EQ(tags.size(), 3u);
    EXPECT_DOUBLE_EQ(tags[0].number(), 1.0);
    EXPECT_TRUE(tags[1].isNull());
    EXPECT_EQ(tags[2].string(), "x");
}

TEST(Json, MissingKeysFallBack) {
    const auto v = json::parse(R"({"a":1})");
    ASSERT_TRUE(v.has_value());
    EXPECT_EQ(v->get("b"), nullptr);
    EXPECT_EQ(v->optString("b", "fallback"), "fallback");
}

TEST(Json, DecodesStringEscapes) {
    const auto v = json::parse(R"(["a\"b\\c\nd"])");
    ASSERT_TRUE(v.has_value());
    EXPECT_EQ(v->array().at(0).string(), "a\"b\\c\nd");
}

TEST(Json, RejectsMalformedInput) {
    EXPECT_FALSE(json::parse("{").has_value());
    EXPECT_FALSE(json::parse(R"({"a":})").has_value());
    EXPECT_FALSE(json::parse("[1,2").has_value());
}
