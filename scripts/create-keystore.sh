#!/usr/bin/env bash
# Creates the release signing key OUTSIDE the repo. Back up both files somewhere safe:
# without them, future builds cannot upgrade the installed app, and reinstalling wipes its data.
set -euo pipefail
# shellcheck disable=SC1091
[ -f "$HOME/.local/share/dev-dl/android-env.sh" ] && source "$HOME/.local/share/dev-dl/android-env.sh"
command -v keytool >/dev/null || { echo "keytool not found; run setup-toolchain.sh first" >&2; exit 1; }
DIR="$HOME/.config/asset-board"
STORE="$DIR/release.jks"
PROPS="$DIR/keystore.properties"
if [ -f "$STORE" ]; then echo "Keystore already exists: $STORE"; exit 0; fi
mkdir -p "$DIR"
chmod 700 "$DIR"
PASS="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
keytool -genkeypair -v -keystore "$STORE" -alias assetboard -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=Asset Board, O=Personal, C=CN"
cat > "$PROPS" <<PROPS
storeFile=$STORE
storePassword=$PASS
keyAlias=assetboard
keyPassword=$PASS
PROPS
chmod 600 "$STORE" "$PROPS"
echo "Created $STORE and $PROPS. Back up both."
