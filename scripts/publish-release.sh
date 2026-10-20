#!/usr/bin/env bash
# Publishes the version in app/build.gradle.kts as a GitHub release: builds the signed APK, tags
# v<versionName>, pushes, and uploads OpenAssetBoard-<versionName>.apk. The in-app updater reads
# the latest non-prerelease release and its first .apk, so the tag and the asset name matter.
# Needs the release keystore (scripts/create-keystore.sh) and `gh auth login`.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
[ -f "$HOME/.local/share/dev-dl/android-env.sh" ] && source "$HOME/.local/share/dev-dl/android-env.sh"

VERSION="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"
TAG="v$VERSION"
[ -n "$VERSION" ] || { echo "versionName not found" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Working tree is not clean" >&2; exit 1; }
# `gh auth status` can fail behind a proxy with a working token; an API call is the real test.
gh api user --jq .login >/dev/null 2>&1 || { echo "Run 'gh auth login' first" >&2; exit 1; }
REPO="$(sed -n 's/.*"UPDATE_REPO", "\\"\(.*\)\\"".*/\1/p' app/build.gradle.kts)"
[ -n "$REPO" ] || { echo "UPDATE_REPO not found" >&2; exit 1; }
git rev-parse "$TAG" >/dev/null 2>&1 && { echo "Tag $TAG already exists" >&2; exit 1; }
grep -q "^## \[$VERSION\]" CHANGELOG.md || { echo "CHANGELOG.md has no [$VERSION] section" >&2; exit 1; }

./gradlew ${GRADLE_ARGS:-} assembleRelease
APK="build/release/OpenAssetBoard-$VERSION.apk"
mkdir -p build/release
cp app/build/outputs/apk/release/app-release.apk "$APK"

# The release notes: this version's CHANGELOG section, without its heading.
NOTES="$(awk -v v="## [$VERSION]" 'index($0, v) == 1 { on = 1; next } on && /^## \[/ { exit } on' CHANGELOG.md)"

git tag -a "$TAG" -m "$TAG"
# Over HTTPS with gh's token: works where SSH's port 22 is blocked.
git -c credential.helper= -c credential.helper='!gh auth git-credential' push "https://github.com/$REPO.git" HEAD:main "$TAG"
git fetch -q origin 2>/dev/null || true
gh release create "$TAG" "$APK" --repo "$REPO" --title "$TAG" --notes "$NOTES"
echo "Published $TAG"
