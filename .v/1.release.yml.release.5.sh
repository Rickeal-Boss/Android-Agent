if ! command -v gradle > /dev/null 2>&1; then
  echo "::error::'gradle' is not on PATH — gradle/actions/setup-gradle failed to install it. Check the 'Set up Gradle' step logs and the gradle-version input."
  exit 1
fi
echo "GRADLE_CMD=gradle" >> "$GITHUB_ENV"
echo "::notice::Using Gradle installed by gradle/actions/setup-gradle: $(gradle --version | head -n 3 | tr '\n' ' ')"
