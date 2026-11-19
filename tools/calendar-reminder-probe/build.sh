#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"

sdk_root=${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}
android_jar="$sdk_root/platforms/android-36/android.jar"
build_tools="$sdk_root/build-tools/36.0.0"
output_dir=out
mkdir -p "$output_dir/classes"

javac --release 8 -cp "$android_jar" -d "$output_dir/classes" ProbeActivity.java
jar cf "$output_dir/classes.jar" -C "$output_dir/classes" .
"$build_tools/d8" --lib "$android_jar" --min-api 26 --output "$output_dir" "$output_dir/classes.jar"
"$build_tools/aapt2" link -I "$android_jar" --manifest AndroidManifest.xml -o "$output_dir/unsigned.apk"
zip -jq "$output_dir/unsigned.apk" "$output_dir/classes.dex"
"$build_tools/zipalign" -f 4 "$output_dir/unsigned.apk" "$output_dir/aligned.apk"

keystore="$output_dir/probe-debug.jks"
if [[ ! -f "$keystore" ]]; then
  keytool -genkeypair -noprompt -keystore "$keystore" -storepass android -keypass android \
    -alias androiddebugkey -dname 'CN=Calendar Reminder Probe Debug' -keyalg RSA -validity 365
fi
"$build_tools/apksigner" sign --ks "$keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$output_dir/calendar-reminder-probe.apk" "$output_dir/aligned.apk"
