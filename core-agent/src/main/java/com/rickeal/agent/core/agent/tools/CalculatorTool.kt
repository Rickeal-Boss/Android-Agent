package com.rickeal.agent.core.agent.tools

import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import java.util.Locale

class CalculatorTool : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "calculator",
        description = "计算数学表达式，支持 + - * / ^ 与括号，例如 (1+2)*3^2",
        parameters = listOf(
            ToolParameter("expression", ToolParamType.STRING, "要计算的表达式", required = true),
        ),
        category = "utility",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val expression = stringArg(argumentsJson, "expression")
        if (expression.isBlank()) {
            return ToolResult(name = spec.name, ok = false, errorMessage = "缺少 expression 参数")
        }
        return try {
            val value = ExpressionEvaluator.evaluate(expression)
            ToolResult(name = spec.name, ok = true, output = formatNumber(value))
        } catch (t: Throwable) {
            ToolResult(name = spec.name, ok = false, errorMessage = "无法计算：${t.message}")
        }
    }

    private fun formatNumber(value: Double): String =
        if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            // 必须钉死 Locale.US：默认 Locale 在德语/法语区把小数点输出成逗号（3,3333333333），
            // 模型很可能把逗号读成千分位 —— 后续推理出错且全程无报错。
            "%.10f".format(Locale.US, value).trimEnd('0').trimEnd('.')
        }
}

/**
 * 极简递归下降求值器。刻意不引 JS 引擎（简报 §6 禁额外依赖）。
 * 支持：+ - * / ^ ( ) 一元正负 小数
 */
object ExpressionEvaluator {

    fun evaluate(expression: String): Double {
        val parser = Parser(expression)
        val value = parser.parseExpression()
        parser.skipWhitespace()
        if (!parser.isEnd()) throw IllegalArgumentException("无法解析：位置 ${parser.position}")
        return value
    }

    private class Parser(private val source: String) {
        var position: Int = 0
            private set

        fun isEnd(): Boolean = position >= source.length

        fun skipWhitespace() {
            while (position < source.length && source[position].isWhitespace()) position++
        }

        private fun peek(): Char? = if (isEnd()) null else source[position]

        private fun expect(char: Char) {
            skipWhitespace()
            if (peek() != char) throw IllegalArgumentException("期望 '$char' 于位置 $position")
            position++
        }

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                when (peek()) {
                    '+' -> { position++; value += parseTerm() }
                    '-' -> { position++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parsePower()
            while (true) {
                skipWhitespace()
                when (peek()) {
                    '*' -> { position++; value *= parsePower() }
                    '/' -> { position++; val divisor = parsePower(); value /= divisor }
                    else -> return value
                }
            }
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            skipWhitespace()
            if (peek() == '^') {
                position++
                return Math.pow(base, parsePower())
            }
            return base
        }

        private fun parseUnary(): Double {
            skipWhitespace()
            when (peek()) {
                '+' -> { position++; return parseUnary() }
                '-' -> { position++; return -parseUnary() }
                '(' -> {
                    position++
                    val value = parseExpression()
                    expect(')')
                    return value
                }
                else -> return parseNumber()
            }
        }

        private fun parseNumber(): Double {
            skipWhitespace()
            val start = position
            while (position < source.length && (source[position].isDigit() || source[position] == '.')) position++
            if (start == position) throw IllegalArgumentException("位置 $position 处缺少数字")
            val literal = source.substring(start, position)
            return literal.toDoubleOrNull() ?: throw IllegalArgumentException("非法数字：$literal")
        }
    }
}
