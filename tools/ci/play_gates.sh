#!/usr/bin/env bash
# Play Store gates on a release APK (docs/ci.md). Runs against the unsigned release build in PR CI;
# signing itself is checked in release.yml, where the upload key exists.
#
#   play_gates.sh <release.apk>
#
# 1. targetSdk meets Play's current floor (config/play/min-target-sdk).
# 2. The merged manifest asks for exactly the permissions in config/play/permissions.allowlist.
#    INTERNET and ACCESS_NETWORK_STATE can never be on it: Geode is offline, and the privacy policy
#    (docs/privacy-policy.html) says so. Changing the list means changing the policy in the same PR.
# 3. zip entries are aligned for 16 KB pages (zipalign -P 16). The ELF LOAD alignment of every .so
#    is checked by the checkNativePageAlignment Gradle task, which finalizes assembleRelease.
set -euo pipefail

apk="${1:?usage: play_gates.sh <release.apk>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Android SDK location not set}}"
build_tools="$(ls -d "$sdk"/build-tools/* | sort -V | tail -n 1)"
aapt2="$build_tools/aapt2"
zipalign="$build_tools/zipalign"
failed=0

min_target="$(tr -d '[:space:]' < "$root/config/play/min-target-sdk")"
target="$("$aapt2" dump badging "$apk" | sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p")"
echo "targetSdk $target (Play floor $min_target)"
if [ -z "$target" ] || [ "$target" -lt "$min_target" ]; then
  echo "::error::targetSdk ${target:-unknown} is below Play's floor of $min_target"
  failed=1
fi

actual="$(mktemp)"
"$aapt2" dump permissions "$apk" \
  | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" \
  | sed 's/^dev\.geode\(\.debug\)\{0,1\}\./${applicationId}./' \
  | sort -u > "$actual"
expected="$(mktemp)"
grep -v '^\s*#' "$root/config/play/permissions.allowlist" | sed '/^\s*$/d' | sort -u > "$expected"
for forbidden in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE; do
  if grep -qx "$forbidden" "$actual"; then
    echo "::error::the release manifest asks for $forbidden; Geode is offline"
    failed=1
  fi
done
if ! diff -u "$expected" "$actual"; then
  echo "::error::release permissions differ from config/play/permissions.allowlist (diff above: - allowlist, + manifest)"
  failed=1
fi

if ! "$zipalign" -c -P 16 4 "$apk" > /dev/null; then
  "$zipalign" -c -P 16 -v 4 "$apk" | grep -v '(OK' || true
  echo "::error::$apk is not zip-aligned for 16 KB pages"
  failed=1
fi

[ "$failed" -eq 0 ] && echo "Play gates passed"
exit "$failed"
