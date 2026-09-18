# library 模块产出的是 .aar 不是 .apk，因此这里允许「没找到 APK」，
# 只有默认任务（:app:assembleDebug）没产出 APK 才算真失败。
apk_path="$(find . -path ./.git -prune -o -type f -name '*.apk' -print 2>/dev/null | head -n 1 || true)"
if [ -n "$apk_path" ]; then
  echo "path=$apk_path" >> "$GITHUB_OUTPUT"
  echo "::notice::Built APK: $apk_path"
elif [ "$GRADLE_TASK" = ":app:assembleDebug" ]; then
  echo "::error::Task :app:assembleDebug succeeded but no .apk was produced"
  find . -path ./.git -prune -o -type d -name outputs -print 2>/dev/null | head -n 20 || true
  exit 1
else
  echo "::notice::Task $GRADLE_TASK produced no APK (library modules output .aar) — nothing to upload."
fi
