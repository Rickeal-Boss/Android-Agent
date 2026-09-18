{
  echo "## LiquidAgent release"
  echo ""
  echo "| Item | Value |"
  echo "|---|---|"
  echo "| Ref | \`${GITHUB_REF}\` |"
  echo "| Debug APK | X |"
  echo "| Signing secrets | X |"
  echo "| Signed release APK | X |"
  echo ""
  if [ "X" != "true" ]; then
    echo "> Signing secrets not configured — only the debug APK was produced. "
    echo "Add \`SIGNING_KEYSTORE_BASE64\`, \`SIGNING_KEYSTORE_PASSWORD\`, \`SIGNING_KEY_ALIAS\`, \`SIGNING_KEY_PASSWORD\` to enable signed releases."
  fi
} >> "$GITHUB_STEP_SUMMARY"
