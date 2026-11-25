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

version_code="${PENDOCK_VERSION_CODE:-60}"
version_name="${PENDOCK_VERSION_NAME:-2.0.0}"

ort_version="1.20.0"
ort_aar="build/deps/onnxruntime-android-${ort_version}.aar"
ort_url="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${ort_version}/onnxruntime-android-${ort_version}.aar"
ort_dir="build/deps/onnxruntime-${ort_version}"
ort_jar="${ort_dir}/classes.jar"

mkdir -p build/deps
if [[ ! -f "$ort_aar" ]]; then
    printf 'Downloading ONNX Runtime Android %s...\n' "$ort_version"
    curl -fsSL -o "${ort_aar}.tmp" "$ort_url"
    mv "${ort_aar}.tmp" "$ort_aar"
fi
if [[ ! -f "$ort_jar" || ! -f "${ort_dir}/lib/arm64-v8a/libonnxruntime.so" ]]; then
    rm -rf "$ort_dir"
    mkdir -p "$ort_dir/lib/arm64-v8a"
    unzip -qo "$ort_aar" classes.jar -d "$ort_dir"
    unzip -qo -j "$ort_aar" "jni/arm64-v8a/*.so" -d "$ort_dir/lib/arm64-v8a"
fi

rm -rf build/generated build/classes build/dex
mkdir -p build/generated build/classes build/dex
"$build_tools/aapt2" compile --dir res -o build/resources.zip
"$build_tools/aapt2" link -I "$android_jar" --manifest AndroidManifest.xml \
    --version-code "$version_code" --version-name "$version_name" --java build/generated \
    -o build/resources.apk build/resources.zip
find src build/generated -name '*.java' > build/sources.txt
javac --release 8 -encoding UTF-8 -classpath "$android_jar:$ort_jar" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
"$build_tools/d8" --min-api 29 --lib "$android_jar" --output build/dex build/classes.jar "$ort_jar"
cp build/resources.apk build/unaligned.apk
(cd build/dex && zip -q ../unaligned.apk classes*.dex)
(cd "$ort_dir" && zip -q "$OLDPWD/build/unaligned.apk" lib/arm64-v8a/*.so)
"$build_tools/zipalign" -f 4 build/unaligned.apk build/aligned.apk

keystore="${PENDOCK_KEYSTORE:-build/pendock-debug.jks}"
key_alias="${PENDOCK_KEY_ALIAS:-pendock}"
ks_pass="${PENDOCK_KEYSTORE_PASS:-android}"
key_pass="${PENDOCK_KEY_PASS:-$ks_pass}"
if [[ ! -f "$keystore" ]]; then
    keytool -genkeypair -keystore "$keystore" -storepass "$ks_pass" -keypass "$key_pass" \
        -alias "$key_alias" -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=PenDock Debug'
fi
"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias "$key_alias" \
    --ks-pass "pass:$ks_pass" --key-pass "pass:$key_pass" --out build/PenDock.apk build/aligned.apk
"$build_tools/apksigner" verify build/PenDock.apk
printf '%s\n' "$PWD/build/PenDock.apk"
