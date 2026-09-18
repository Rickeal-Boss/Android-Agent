ks_path="${RUNNER_TEMP}/liquidagent-release.jks"
printf '%s' "$KS_B64" | base64 -d > "$ks_path"
if [ ! -s "$ks_path" ]; then
  echo "::error::Decoded keystore is empty; check SIGNING_KEYSTORE_BASE64"
  exit 1
fi
echo "path=$ks_path" >> "$GITHUB_OUTPUT"
