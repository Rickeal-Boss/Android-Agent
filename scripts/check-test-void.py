#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守卫：测试源集里 @Test 方法必须返回 void（末语句不得是返回值型断言）。

为什么需要这条守卫（Wave 37 实案）：
  JUnit 4 要求测试方法返回 `void`。Kotlin 的表达式体 `fun x() = ...` 返回类型由
  **末表达式**决定，而 `kotlin.test` 里有四个断言会**返回值**：
      assertNotNull(actual, msg) -> actual
      assertIs<T>(value)         -> value
      assertFailsWith<E> {...}   -> E
      assertFails { ... }        -> Throwable
  把它们放在末语句 ⇒ 方法非 void ⇒ JUnit 校验失败 ⇒ **整个测试类 initializationError**
  ⇒ **该类所有用例一个都不跑**。而 Gradle 只报 `182 tests completed, 1 failed`
  —— 「只挂了 1 个」是**假象**，实际是整类静默不跑。本波为此白烧了一轮 CI。

  （`assertEquals` / `assertTrue` / `assertFalse` / `assertNull` / `assertSame` /
   `assertContains` 都返回 Unit，可以收尾。）

契约（与 scripts/arch-guard.sh 的 check() 一致）：
  - **stdout 非空 = 违规**；干净时**必须零输出**。
  - 永远 exit 0（脚本自身异常时把 traceback 打到 stdout，从而被判红而不是静默通过）。
  用法：python scripts/check-test-void.py [-v]   （在仓库根目录执行；-v 打印统计）
"""
import os
import re
import sys
import traceback

VALUE_RETURNING = ("assertNotNull", "assertIs", "assertFailsWith", "assertFails")
BAD_LAST = re.compile(r"^\s*(" + "|".join(VALUE_RETURNING) + r")\b")
BAD_INLINE = re.compile(r"^\s*fun\b.*\)\s*=\s*(" + "|".join(VALUE_RETURNING) + r")\b")
PRUNE = {".git", "build", ".gradle", ".kotlin", ".idea"}


def strip_code(line: str) -> str:
    """去掉行内字符串字面量与行注释，便于数花括号、判语句。"""
    out, i, n = [], 0, len(line)
    in_str = in_chr = False
    while i < n:
        c = line[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if in_chr:
            if c == "\\":
                i += 2
                continue
            if c == "'":
                in_chr = False
            i += 1
            continue
        if c == '"':
            if line[i:i + 3] == '"""':
                i += 3
                continue
            in_str = True
            i += 1
            continue
        if c == "'":
            in_chr = True
            i += 1
            continue
        if line[i:i + 2] == "//":
            break
        out.append(c)
        i += 1
    return "".join(out)


def scan_file(path: str):
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    depth = 0
    pending = False
    pending_depth = 0
    fn_line = None
    fn_depth = 0
    body = []
    for idx, raw in enumerate(lines, 1):
        code = strip_code(raw)

        if re.match(r"^\s*@Test\b", raw):
            pending = True
            pending_depth = depth

        if pending and re.search(r"\bfun\b", code) and depth == pending_depth:
            fn_line = idx
            fn_depth = depth
            pending = False
            if "{" not in code and BAD_INLINE.match(code):
                yield (idx, idx, raw.strip())
                fn_line = None

        opens = code.count("{")
        closes = code.count("}")
        depth += opens - closes

        if fn_line is not None:
            body.append((idx, raw))
            if depth == fn_depth and (opens or closes):
                last = None
                for ln, txt in body:
                    if txt.strip() and txt.strip() not in ("{", "}"):
                        last = (ln, txt)
                if last and BAD_LAST.match(strip_code(last[1])):
                    yield (fn_line, last[0], last[1].strip())
                fn_line = None
                body = []


def main() -> int:
    verbose = "-v" in sys.argv
    findings = []
    scanned = 0
    for dirpath, dirnames, filenames in os.walk("."):
        dirnames[:] = [d for d in dirnames if d not in PRUNE]
        norm = dirpath.replace("\\", "/")
        if "/src/test" not in norm:
            continue
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            scanned += 1
            full = os.path.join(dirpath, fn)
            for fn_line, ln, txt in scan_file(full):
                findings.append((full.replace("\\", "/"), fn_line, ln, txt))

    if verbose:
        sys.stderr.write("（扫描测试源文件 %d 个）\n" % scanned)
    if findings:
        print("测试源集里有 @Test 方法以「返回值型断言」收尾 —— JUnit 4 要求测试方法返回 void，")
        print("这类方法会让**整个测试类** initializationError（该类所有用例都不跑）：")
        for f, fl, ln, txt in findings:
            print("  %s:%d  (fun @%d)" % (f, ln, fl))
            print("      %s" % txt)
        print("修法：把该断言绑到 val 再补一句 Unit 型断言，例如")
        print("      val m = assertNotNull(x.userMessage, \"...\")")
        print("      assertTrue(m.contains(\"...\"), \"...\")")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        # 契约要求「stdout 非空 = 违规」⇒ 自身异常也必须打到 stdout，绝不能静默通过
        print("check-test-void.py 自身执行失败（按违规处理，防静默失效）：")
        traceback.print_exc(file=sys.stdout)
        sys.exit(0)
