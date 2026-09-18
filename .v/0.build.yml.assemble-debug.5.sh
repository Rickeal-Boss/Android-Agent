if ! command -v gradle > /dev/null 2>&1; then
  echo "::error::'gradle' is not on PATH — gradle/actions/setup-gradle failed to install it. Check the 'Set up Gradle' step logs and the gradle-version input."
  exit 1
fi
echo "GRADLE_CMD=gradle" >> "$GITHUB_ENV"
echo "::notice::Using Gradle installed by gradle/actions/setup-gradle: $(gradle --version | head -n 3 | tr '\n' ' ')"

# 解析要执行的任务：手动触发时可用 inputs.gradle_task 做模块级验证。
# 白名单校验：dispatch input 会被插值进 shell，只允许 Gradle 任务名字符集，
# 避免注入（如 "; curl ..."）。
task="X"
if [ -z "$task" ]; then task=":app:assembleDebug"; fi
case "$task" in
  *[!A-Za-z0-9_:.\-]* )
    echo "::error::Invalid gradle_task: '$task'. Only [A-Za-z0-9_:.-] is allowed."
    exit 1 ;;
esac
echo "GRADLE_TASK=$task" >> "$GITHUB_ENV"
echo "::notice::Gradle task: $task"
