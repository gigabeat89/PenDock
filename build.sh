#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
build_tools="${ANDROID_BUILD_TOOLS:-$sdk/build-tools/35.0.0}"
android_jar="${ANDROID_JAR:-$sdk/platforms/android-35/android.jar}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
export PATH="$JAVA_HOME/bin:$PATH"
if [[ ! -x "$build_tools/aapt2" || ! -f "$android_jar" ]]; then
    printf '%s\n' 'Install Android SDK Platform 35 and Build Tools 35.0.0.'
    exit 1
fi

rm -rf build/generated build/classes build/dex
mkdir -p build/generated build/classes build/dex
"$build_tools/aapt2" compile --dir res -o build/resources.zip
"$build_tools/aapt2" link -I "$android_jar" --manifest AndroidManifest.xml \
    --version-code 49 --version-name 1.0.3 --java build/generated \
    -o build/resources.apk build/resources.zip
find src build/generated -name '*.java' > build/sources.txt
javac --release 8 -encoding UTF-8 -classpath "$android_jar" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
"$build_tools/d8" --min-api 29 --lib "$android_jar" --output build/dex build/classes.jar
cp build/resources.apk build/unaligned.apk
(cd build/dex && zip -q ../unaligned.apk classes.dex)
"$build_tools/zipalign" -f 4 build/unaligned.apk build/aligned.apk

keystore="${PENDOCK_KEYSTORE:-build/pendock-debug.jks}"
if [[ ! -f "$keystore" ]]; then
    keytool -genkeypair -keystore "$keystore" -storepass android -keypass android \
        -alias pendock -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=PenDock Debug'
fi
"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias pendock \
    --ks-pass pass:android --key-pass pass:android --out build/PenDock.apk build/aligned.apk
"$build_tools/apksigner" verify build/PenDock.apk
printf '%s\n' "$PWD/build/PenDock.apk"
