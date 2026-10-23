#!/usr/bin/env bash
# Publishes the version in app/build.gradle.kts as a GitHub release: builds the signed APK, tags
# v<versionName>, pushes, and uploads OpenAssetBoard-<versionName>.apk with the build's R8 mapping.
# The in-app updater reads the latest non-prerelease release and its first .apk, so the tag and
# the asset name matter. Needs the release keystore (scripts/create-keystore.sh) and `gh auth login`.
#
# Before anything is tagged, the gate: versionCode above the last release's (the updater refuses
# anything else, silently for every user), the unit tests (checking the screenshots against
# app/screenshots when that folder has any), lint against its baseline, and after the build the
# APK's signing certificate against the release key's.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
[ -f "$HOME/.local/share/dev-dl/android-env.sh" ] && source "$HOME/.local/share/dev-dl/android-env.sh"

# The release key's certificate; every release so far is signed with it.
RELEASE_CERT_SHA256="150609abe6d702ca6c254d3b413dd51c361884b9367461ab40f2c1a756d6f95d"

VERSION="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"
CODE="$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' app/build.gradle.kts)"
TAG="v$VERSION"
[ -n "$VERSION" ] && [ -n "$CODE" ] || { echo "versionName or versionCode not found" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Working tree is not clean" >&2; exit 1; }
# `gh auth status` can fail behind a proxy with a working token; an API call is the real test.
gh api user --jq .login >/dev/null 2>&1 || { echo "Run 'gh auth login' first" >&2; exit 1; }
REPO="$(sed -n 's/.*"UPDATE_REPO", "\\"\(.*\)\\"".*/\1/p' app/build.gradle.kts)"
[ -n "$REPO" ] || { echo "UPDATE_REPO not found" >&2; exit 1; }
git rev-parse "$TAG" >/dev/null 2>&1 && { echo "Tag $TAG already exists" >&2; exit 1; }
grep -q "^## \[$VERSION\]" CHANGELOG.md || { echo "CHANGELOG.md has no [$VERSION] section" >&2; exit 1; }

PREV_TAG="$(git describe --tags --abbrev=0 2>/dev/null || true)"
if [ -n "$PREV_TAG" ]; then
    PREV_CODE="$(git show "$PREV_TAG:app/build.gradle.kts" | sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p')"
    [ "$CODE" -gt "${PREV_CODE:-0}" ] || { echo "versionCode $CODE is not above $PREV_TAG's $PREV_CODE: no installed copy would take this update" >&2; exit 1; }
fi

VERIFY=()
compgen -G "app/screenshots/*.png" >/dev/null && VERIFY=(-Proborazzi.test.verify=true)
./gradlew ${GRADLE_ARGS:-} testDebugUnitTest "${VERIFY[@]}" lintRelease

./gradlew ${GRADLE_ARGS:-} assembleRelease
APK="build/release/OpenAssetBoard-$VERSION.apk"
MAPPING="build/release/mapping-$VERSION.txt"
mkdir -p build/release
cp app/build/outputs/apk/release/app-release.apk "$APK"
# R8's mapping: the only way to read a stack in this build's crash log.
cp app/build/outputs/mapping/release/mapping.txt "$MAPPING"

APKSIGNER="$(compgen -G "${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/*/apksigner" | sort -V | tail -1)"
[ -n "$APKSIGNER" ] || { echo "apksigner not found" >&2; exit 1; }
CERT="$("$APKSIGNER" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[ "$CERT" = "$RELEASE_CERT_SHA256" ] || { echo "The APK is signed with ${CERT:-no certificate}, not the release key: installed copies could not update" >&2; exit 1; }

# The release notes: this version's CHANGELOG section, without its heading.
NOTES="$(awk -v v="## [$VERSION]" 'index($0, v) == 1 { on = 1; next } on && /^## \[/ { exit } on' CHANGELOG.md)"

git tag -a "$TAG" -m "$TAG"
# Over HTTPS with gh's token: works where SSH's port 22 is blocked.
git -c credential.helper= -c credential.helper='!gh auth git-credential' push "https://github.com/$REPO.git" HEAD:main "$TAG"
git fetch -q origin 2>/dev/null || true
gh release create "$TAG" "$APK" "$MAPPING" --repo "$REPO" --title "$TAG" --notes "$NOTES"
echo "Published $TAG"
