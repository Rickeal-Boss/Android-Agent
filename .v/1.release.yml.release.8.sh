"$GRADLE_CMD" \
  --no-daemon \
  --stacktrace \
  "-Dorg.gradle.jvmargs=${GRADLE_JVM_ARGS}" \
  :app:assembleDebug
