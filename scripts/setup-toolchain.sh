#!/usr/bin/env bash
# Installs everything needed to build asset-board into the user's home directory (no sudo):
# JDK 21, Android SDK (platform 36, build-tools, platform-tools) and Gradle, then generates the
# Gradle wrapper. Running this accepts the Android SDK licenses.
set -euo pipefail

GRADLE_VERSION=9.8.1
DL="$HOME/.local/share/dev-dl"
JDK_ROOT="$HOME/.local/jdk"
SDK="$HOME/Android/Sdk"
GRADLE_HOME="$HOME/.local/gradle-$GRADLE_VERSION"
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$DL" "$JDK_ROOT" "$SDK/cmdline-tools"

unzip_py() { python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$1" "$2"; }

if ! ls "$JDK_ROOT"/jdk-21*/bin/java >/dev/null 2>&1; then
  echo ">> JDK 21"
  curl -fL -o "$DL/jdk21.tar.gz" "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar -xzf "$DL/jdk21.tar.gz" -C "$JDK_ROOT"
fi
JAVA_HOME="$(ls -d "$JDK_ROOT"/jdk-21* | head -1)"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo ">> Android cmdline-tools"
  curl -fL -o "$DL/cmdtools.zip" https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
  unzip_py "$DL/cmdtools.zip" "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  chmod +x "$SDK/cmdline-tools/latest/bin/"*
fi

cat > "$DL/android-env.sh" <<ENV
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:$GRADLE_HOME/bin:\$PATH"
ENV
# shellcheck disable=SC1091
source "$DL/android-env.sh"

# Java tools ignore http(s)_proxy, so translate it for sdkmanager and Gradle.
SDK_PROXY=()
GRADLE_PROXY=()
if [ -n "${https_proxy:-}" ]; then
  hostport="${https_proxy#*://}"; hostport="${hostport%/}"
  host="${hostport%:*}"; port="${hostport##*:}"
  SDK_PROXY=(--proxy=http --proxy_host="$host" --proxy_port="$port")
  GRADLE_PROXY=(-Dhttp.proxyHost="$host" -Dhttp.proxyPort="$port" -Dhttps.proxyHost="$host" -Dhttps.proxyPort="$port")
fi

echo ">> Android SDK licenses and packages"
# `yes` dies of SIGPIPE once sdkmanager stops reading; that is expected, not a failure.
(yes || true) | sdkmanager "${SDK_PROXY[@]}" --licenses >/dev/null || true
(yes || true) | sdkmanager "${SDK_PROXY[@]}" "platform-tools" "platforms;android-36" "platforms;android-37.0" "build-tools;36.1.0"
test -d "$SDK/platforms/android-36" || { echo "platform android-36 missing after install" >&2; exit 1; }

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  echo ">> Gradle $GRADLE_VERSION"
  curl -fL -o "$DL/gradle.zip" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  unzip_py "$DL/gradle.zip" "$HOME/.local"
  chmod +x "$GRADLE_HOME/bin/gradle"
fi

echo "sdk.dir=$SDK" > "$PROJECT/local.properties"
if [ ! -f "$PROJECT/gradlew" ]; then
  echo ">> Gradle wrapper"
  (cd "$PROJECT" && gradle "${GRADLE_PROXY[@]}" wrapper --gradle-version "$GRADLE_VERSION")
fi
echo "Done. Next: source $DL/android-env.sh"
