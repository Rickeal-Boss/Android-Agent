if [ -n "${KS_B64:-}" ] && [ -n "${KS_PASSWORD:-}" ] && [ -n "${KS_ALIAS:-}" ] && [ -n "${KS_KEY_PASSWORD:-}" ]; then
  echo "available=true" >> "$GITHUB_OUTPUT"
  echo "::notice::Signing secrets detected, release APK will be signed."
else
  echo "available=false" >> "$GITHUB_OUTPUT"
  echo "::notice::Signing secrets NOT configured (SIGNING_KEYSTORE_BASE64 / SIGNING_KEYSTORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD). Skipping signed release build; shipping debug APK instead."
fi
