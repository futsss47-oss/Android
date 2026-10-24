package ru.overtime.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class FormulaException(message: String, val position: Int = -1) : Exception(message)

/**
 * Безопасный вычислитель формул оплаты (без eval и внешних библиотек).
 *
 * Числа пишутся через точку: 1.5. Аргументы функций разделяются запятой или «;».
 * Операции: + - * / % ^, сравнения < > <= >= == !=, логика && || !
 * Функции: min, max, abs, round(x) / round(x, знаков), floor, ceil, sqrt, pow, if(условие, да, нет)
 * Переменные: см. [VARIABLES].
 */
object Formula {

    val VARIABLES: Map<String, String> = linkedMapOf(
        "h" to "часы сверхурочной за день (с учётом округления)",
        "m" to "минуты сверхурочной за день (с учётом округления)",
        "rate" to "ставка, руб/час (из настроек)",
        "salary" to "оклад, руб/мес (из настроек)",
        "norm" to "норма часов в месяце (из настроек)",
        "weekend" to "1, если день выходной по вашему графику, иначе 0 (формулы будней и выходных выбираются сами)",
        "dow" to "день недели: 1 = понедельник ... 7 = воскресенье",
    )

    private val SAMPLE: Map<String, Double> = mapOf(
        "h" to 2.5, "m" to 150.0, "rate" to 100.0, "salary" to 50000.0,
        "norm" to 168.0, "weekend" to 0.0, "dow" to 3.0,
    )

    private val FUNCTIONS = setOf("min", "max", "abs", "round", "floor", "ceil", "sqrt", "pow", "if")

    fun evaluate(src: String, vars: Map<String, Double>): Double {
        val result = Parser(src).parseAll().eval(vars)
        if (result.isNaN() || result.isInfinite()) throw FormulaException("Результат не является числом")
        return result
    }

    /** Возвращает текст ошибки или null, если формула корректна. */
    fun validate(src: String): String? = try {
        evaluate(src, SAMPLE)
        null
    } catch (e: FormulaException) {
        if (e.position >= 0) "${e.message} (позиция ${e.position + 1})" else e.message
    }

    // ---- дерево выражения ----

    private interface Node {
        fun eval(env: Map<String, Double>): Double
    }

    private class Num(val v: Double) : Node {
        override fun eval(env: Map<String, Double>) = v
    }

    private class Var(val name: String, val pos: Int) : Node {
        override fun eval(env: Map<String, Double>): Double =
            env[name] ?: throw FormulaException("Неизвестная переменная «$name»", pos)
    }

    private class Unary(val op: Char, val a: Node) : Node {
        override fun eval(env: Map<String, Double>): Double {
            val x = a.eval(env)
            return when (op) {
                '-' -> -x
                '!' -> if (x == 0.0) 1.0 else 0.0
                else -> x
            }
        }
    }

    private class Binary(val op: String, val a: Node, val b: Node, val pos: Int) : Node {
        override fun eval(env: Map<String, Double>): Double {
            if (op == "&&") return if (a.eval(env) != 0.0 && b.eval(env) != 0.0) 1.0 else 0.0
            if (op == "||") return if (a.eval(env) != 0.0 || b.eval(env) != 0.0) 1.0 else 0.0
            val x = a.eval(env)
            val y = b.eval(env)
            return when (op) {
                "+" -> x + y
                "-" -> x - y
                "*" -> x * y
                "/" -> if (y == 0.0) throw FormulaException("Деление на ноль", pos) else x / y
                "%" -> if (y == 0.0) throw FormulaException("Деление на ноль", pos) else x % y
                "^" -> x.pow(y)
                "<" -> bool(x < y)
                ">" -> bool(x > y)
                "<=" -> bool(x <= y)
                ">=" -> bool(x >= y)
                "==" -> bool(x == y)
                "!=" -> bool(x != y)
                else -> throw FormulaException("Неизвестная операция «$op»", pos)
            }
        }

        private fun bool(b: Boolean) = if (b) 1.0 else 0.0
    }

    private class Call(val name: String, val args: List<Node>, val pos: Int) : Node {
        override fun eval(env: Map<String, Double>): Double {
            if (name == "if") {
                // Вычисляем лениво: ненужная ветка не должна падать (например, деление на ноль).
                return if (args[0].eval(env) != 0.0) args[1].eval(env) else args[2].eval(env)
            }
            val v = args.map { it.eval(env) }
            return when (name) {
                "min" -> v.reduce { a, b -> min(a, b) }
                "max" -> v.reduce { a, b -> max(a, b) }
                "abs" -> abs(v[0])
                "floor" -> floor(v[0])
                "ceil" -> ceil(v[0])
                "sqrt" -> if (v[0] < 0) throw FormulaException("sqrt от отрицательного числа", pos) else sqrt(v[0])
                "pow" -> v[0].pow(v[1])
                "round" -> if (v.size == 1) Math.round(v[0]).toDouble() else {
                    val k = 10.0.pow(v[1])
                    Math.round(v[0] * k) / k
                }
                else -> throw FormulaException("Неизвестная функция «$name»", pos)
            }
        }
    }

    // ---- разбор ----

    private class Parser(val s: String) {
        var pos = 0

        fun parseAll(): Node {
            if (s.isBlank()) throw FormulaException("Формула пустая")
            val n = parseOr()
            skipWs()
            if (pos < s.length) throw FormulaException("Лишний символ «${s[pos]}»", pos)
            return n
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun accept(tok: String): Boolean {
            skipWs()
            if (s.startsWith(tok, pos)) {
                pos += tok.length
                return true
            }
            return false
        }

        private fun parseOr(): Node {
            var l = parseAnd()
            while (true) {
                skipWs()
                val p = pos
                if (accept("||")) l = Binary("||", l, parseAnd(), p) else return l
            }
        }

        private fun parseAnd(): Node {
            var l = parseCmp()
            while (true) {
                skipWs()
                val p = pos
                if (accept("&&")) l = Binary("&&", l, parseCmp(), p) else return l
            }
        }

        private fun parseCmp(): Node {
            val l = parseAdd()
            skipWs()
            val p = pos
            for (op in listOf("<=", ">=", "==", "!=", "<", ">")) {
                if (accept(op)) return Binary(op, l, parseAdd(), p)
            }
            if (pos < s.length && s[pos] == '=') throw FormulaException("Для сравнения используйте ==", pos)
            return l
        }

        private fun parseAdd(): Node {
            var l = parseMul()
            while (true) {
                skipWs()
                val p = pos
                l = when {
                    accept("+") -> Binary("+", l, parseMul(), p)
                    accept("-") -> Binary("-", l, parseMul(), p)
                    else -> return l
                }
            }
        }

        private fun parseMul(): Node {
            var l = parseUnary()
            while (true) {
                skipWs()
                val p = pos
                l = when {
                    accept("*") -> Binary("*", l, parseUnary(), p)
                    accept("/") -> Binary("/", l, parseUnary(), p)
                    accept("%") -> Binary("%", l, parseUnary(), p)
                    else -> return l
                }
            }
        }

        private fun parseUnary(): Node {
            skipWs()
            if (accept("-")) return Unary('-', parseUnary())
            if (accept("+")) return Unary('+', parseUnary())
            // «!=» здесь невозможен: в позиции операнда он не встречается
            if (accept("!")) return Unary('!', parseUnary())
            return parsePow()
        }

        private fun parsePow(): Node {
            val base = parsePrimary()
            skipWs()
            val p = pos
            if (accept("^")) return Binary("^", base, parseUnary(), p)
            return base
        }

        private fun parsePrimary(): Node {
            skipWs()
            if (pos >= s.length) throw FormulaException("Формула оборвана", pos)
            val c = s[pos]
            if (c == '(') {
                pos++
                val n = parseOr()
                if (!accept(")")) throw FormulaException("Не хватает «)»", pos)
                return n
            }
            if (c.isDigit() || c == '.') return parseNumber()
            if (c.isLetter() || c == '_') return parseIdent()
            throw FormulaException("Ожидалось число, переменная или «(», найдено «$c»", pos)
        }

        private fun parseNumber(): Node {
            val start = pos
            var dot = false
            while (pos < s.length && (s[pos].isDigit() || (s[pos] == '.' && !dot))) {
                if (s[pos] == '.') dot = true
                pos++
            }
            val text = s.substring(start, pos)
            val v = text.toDoubleOrNull() ?: throw FormulaException("Некорректное число «$text»", start)
            return Num(v)
        }

        private fun parseIdent(): Node {
            val start = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
            val name = s.substring(start, pos).lowercase()
            skipWs()
            if (pos < s.length && s[pos] == '(') {
                if (name !in FUNCTIONS) throw FormulaException("Неизвестная функция «$name»", start)
                pos++
                val args = mutableListOf<Node>()
                skipWs()
                if (!accept(")")) {
                    while (true) {
                        args += parseOr()
                        if (accept(",") || accept(";")) continue
                        if (accept(")")) break
                        throw FormulaException("Ожидалось «,» или «)»", pos)
                    }
                }
                checkArity(name, args.size, start)
                return Call(name, args, start)
            }
            return Var(name, start)
        }

        private fun checkArity(name: String, n: Int, at: Int) {
            val ok = when (name) {
                "min", "max" -> n >= 2
                "abs", "floor", "ceil", "sqrt" -> n == 1
                "round" -> n == 1 || n == 2
                "pow" -> n == 2
                "if" -> n == 3
                else -> true
            }
            if (!ok) throw FormulaException("Неверное число аргументов у «$name»", at)
        }
    }
}
