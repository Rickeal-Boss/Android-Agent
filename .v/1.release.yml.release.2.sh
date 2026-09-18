sdk_root="${ANDROID_HOME:-}"
if [ -z "$sdk_root" ] || [ ! -d "$sdk_root" ]; then
  for candidate in "$HOME/android-sdk" "/usr/local/lib/android/sdk" "$HOME/Android/Sdk" "/opt/android-sdk"; do
    if [ -d "$candidate" ]; then sdk_root="$candidate"; break; fi
  done
fi
if [ -z "$sdk_root" ]; then sdk_root="$HOME/android-sdk"; fi
echo "ANDROID_HOME=$sdk_root" >> "$GITHUB_ENV"
echo "ANDROID_SDK_ROOT=$sdk_root" >> "$GITHUB_ENV"

mkdir -p "$sdk_root/licenses"
printf '%s\n%s\n' \
  '24333f8a63b6825ea9c5514f83c2829b004d1fee' \
  '8933bad161af4178b1185d1a37fbf41ea5269c55' \
  > "$sdk_root/licenses/android-sdk-license"
printf '%s\n' '84831b9409646a7e0bab842799117058b6a3a0a6' \
  > "$sdk_root/licenses/android-sdk-preview-license"
printf '%s\n' 'd975f751698a77b662f1254ddbeed3901e976f5a' \
  > "$sdk_root/licenses/intel-android-extra-license"
printf '%s\n' '601085b94cd77f0b54ff86406957099beebea79f5' \
  > "$sdk_root/licenses/android-googletv-license"
if [ -x "$sdk_root/cmdline-tools/latest/bin/sdkmanager" ]; then
  yes | "$sdk_root/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
fi
