"$GRADLE_CMD" \
  --no-daemon \
  --stacktrace \
  "-Dorg.gradle.jvmargs=${GRADLE_JVM_ARGS}" \
  -Psigning.storeFile="X" \
  -Psigning.storePassword="${KS_PASSWORD}" \
  -Psigning.keyAlias="${KS_ALIAS}" \
  -Psigning.keyPassword="${KS_KEY_PASSWORD}" \
  :app:assembleRelease
