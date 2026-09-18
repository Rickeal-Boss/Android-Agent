{
  echo "## LiquidAgent build"
  echo ""
  echo "| Item | Value |"
  echo "|---|---|"
  echo "| Commit | \`${GITHUB_SHA}\` |"
  echo "| Ref | \`${GITHUB_REF}\` |"
  echo "| JDK | X (X) |"
  echo "| Gradle | 9.7.1 (setup-gradle) |"
  echo "| Task | \`${GRADLE_TASK}\` |"
  echo ""
  if [ "X" = "success" ]; then
    echo "**Result:** build succeeded (artifact retention X days)."
  else
    echo "**Result:** FAILED — inspect the \`build-reports-\` artifact and the Annotations panel."
    echo ""
    echo "> 若报错含 Compose 编译器 / Kotlin 版本不匹配，按简报 §4 只把 Kotlin 降到 **2.2.21**，不要动 Compose BOM。"
  fi
} >> "$GITHUB_STEP_SUMMARY"
