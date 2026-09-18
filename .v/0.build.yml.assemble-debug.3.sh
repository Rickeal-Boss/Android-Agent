mkdir -p "$HOME/.gradle"
if [ -f .github/ci-gradle.properties ]; then
  cat .github/ci-gradle.properties > "$HOME/.gradle/gradle.properties"
  echo "::group::Effective CI gradle.properties"
  cat "$HOME/.gradle/gradle.properties"
  echo "::endgroup::"
else
  echo "::warning::.github/ci-gradle.properties not found, using project defaults"
fi
