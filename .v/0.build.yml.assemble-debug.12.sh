echo "::group::JDK"
java -version 2>&1 || true
echo "::endgroup::"
echo "::group::SDK"
ls "$ANDROID_HOME" 2>/dev/null | head -n 30 || true
ls "$ANDROID_HOME/platforms" 2>/dev/null || true
ls "$ANDROID_HOME/build-tools" 2>/dev/null || true
echo "::endgroup::"
echo "::group::Workspace tree (build outputs)"
find . -path ./.git -prune -o -type d -name build -print 2>/dev/null | head -n 20 || true
echo "::endgroup::"
echo "See the 'Upload failure reports' artifact for full Gradle reports."
