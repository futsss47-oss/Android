#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?}" "${SIGNING_STORE:?}" "${SIGNING_PASSWORD:?}"
code="${1:?version code}" 
out="${2:?absolute APK path}"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
bt="$ANDROID_HOME/build-tools/35.0.0"
platform="$ANDROID_HOME/platforms/android-35/android.jar"
mkdir -p "$tmp/classes" "$tmp/dex" "$(dirname "$out")"
"$bt/aapt2" compile --dir res -o "$tmp/res.zip"
"$bt/aapt2" link -I "$platform" --manifest AndroidManifest.xml --version-code "$code" --version-name "0.1.$code" -A assets -o "$tmp/base.apk" "$tmp/res.zip"
javac --release 8 -classpath "$platform" -d "$tmp/classes" src/ru/futsss47/kordon/GameActivity.java
jar cf "$tmp/classes.jar" -C "$tmp/classes" .
"$bt/d8" --release --min-api 24 --lib "$platform" --output "$tmp/dex" "$tmp/classes.jar"
(cd "$tmp/dex" && zip -q "$tmp/base.apk" classes.dex)
"$bt/zipalign" -f -p 4 "$tmp/base.apk" "$tmp/aligned.apk"
"$bt/apksigner" sign --ks "$SIGNING_STORE" --ks-key-alias overtime --ks-pass env:SIGNING_PASSWORD --key-pass env:SIGNING_PASSWORD --out "$out" "$tmp/aligned.apk"
"$bt/apksigner" verify --verbose --print-certs "$out"
