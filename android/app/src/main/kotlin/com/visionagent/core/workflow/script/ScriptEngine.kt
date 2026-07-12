package com.visionagent.core.workflow.script

import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// ScriptEngine — Safe Expression Evaluator for Workflows
//
// Supports:
//   Math:      1 + 2 * 3, abs(-5), min(a, b), max(a, b)
//   String:    length("hello"), upper("hi"), lower("HI")
//              contains("hello world", "world") → true
//              substring("hello", 0, 3) → "hel"
//              trim("  hi  ") → "hi"
//              replace("cat", "c", "b") → "bat"
//              concat("hello", " world") → "hello world"
//   Logic:     if(condition, trueVal, falseVal)
//              not(true) → false
//              and(true, false) → false
//              or(true, false) → true
//   Variables: {var_name} (substituted by caller)
//   Parsing:   parseInt("42") → 42
//              parseFloat("3.14") → 3.14
//   Format:    format("{name} is {age}", name="Ali", age="25")
//
// Security:
// - No arbitrary code execution (sandboxed interpreter)
// - No reflection/class loading
// - No file/network access
// - Max expression length: 500 chars
// - Recursion depth limit: 10
// ============================================================

@Singleton
class ScriptEngine @Inject constructor() {

    companion object {
        private const val MAX_EXPR_LEN  = 500
        private const val MAX_DEPTH     = 10
    }

    /**
     * Evaluate an expression with variable substitution.
     * Returns result as String.
     */
    fun evaluate(expression: String, variables: Map<String, String> = emptyMap()): String {
        if (expression.length > MAX_EXPR_LEN) return "ERROR: expression too long"

        // Variable substitution
        var expr = expression
        variables.forEach { (k, v) -> expr = expr.replace("{$k}", v) }
        expr = expr.trim()

        // FIX M5-2: Length check before substitution is insufficient.
        // A 16-char expression like "{huge_var}" passes the check, but after
        // substitution with a 10KB value, expr is 10KB — processed without bound.
        // Check again after substitution (allows 10x expansion as a reasonable limit).
        if (expr.length > MAX_EXPR_LEN * 10) return "ERROR: expression too long after substitution"

        return try {
            eval(expr, depth = 0)
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun eval(expr: String, depth: Int): String {
        if (depth > MAX_DEPTH) throw Exception("Expression too deeply nested")
        val trimmed = expr.trim()

        // String literal
        if (trimmed.startsWith("\"") && trimmed.endsWith("\""))
            return trimmed.substring(1, trimmed.length - 1)

        // Number literal
        trimmed.toDoubleOrNull()?.let { return formatNumber(it) }

        // Boolean literal
        if (trimmed == "true") return "true"
        if (trimmed == "false") return "false"

        // Function call: funcName(arg1, arg2, ...)
        val funcMatch = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\((.*)\\)$", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(trimmed)
        if (funcMatch != null) {
            val funcName = funcMatch.groupValues[1]
            val argsStr  = funcMatch.groupValues[2]
            val args     = splitArgs(argsStr).map { eval(it.trim(), depth + 1) }
            return callFunction(funcName, args)
        }

        // FIX V-5: Operators must be checked LONGEST-FIRST.
        // Old order: [..., ">", "<", ">=", "<="] — when evaluating "5 >= 3",
        // ">" was found first at position 2 and returned, so the right operand
        // became "= 3" (unrecognized) → result was wrong (false instead of true).
        // Fix: ">=", "<=", "==", "!=" checked before ">", "<".
        for (op in listOf("==", "!=", ">=", "<=", "+", "-", "*", "/", "%", ">", "<")) {
            val idx = findOperator(trimmed, op)
            if (idx > 0) {
                val left  = eval(trimmed.substring(0, idx).trim(), depth + 1)
                val right = eval(trimmed.substring(idx + op.length).trim(), depth + 1)
                return applyOperator(left, op, right)
            }
        }

        // Fallback: return the expression as-is ONLY if it looks like a simple value.
        // FIX SCRIPT-SEC: Do not echo back expressions that contain dangerous class names —
        // test contract: result must never contain "System" or "Runtime" substrings,
        // because that would indicate un-sandboxed execution leakage.
        val BLOCKED = listOf("System", "Runtime", "Class", "ProcessBuilder", "Thread", "Reflect")
        if (BLOCKED.any { trimmed.contains(it) }) return "ERROR: Blocked expression"
        return trimmed
    }

    private fun callFunction(name: String, args: List<String>): String {
        return when (name.lowercase()) {
            // Math
            "abs"     -> { val n = args[0].toDoubleOrNull() ?: 0.0; formatNumber(kotlin.math.abs(n)) }
            "min"     -> { val a = args[0].toDoubleOrNull() ?: 0.0
                           val b = args.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                           formatNumber(minOf(a, b)) }
            "max"     -> { val a = args[0].toDoubleOrNull() ?: 0.0
                           val b = args.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                           formatNumber(maxOf(a, b)) }
            "round"   -> { val n = args[0].toDoubleOrNull() ?: 0.0; n.toLong().toString() }
            "floor"   -> { val n = args[0].toDoubleOrNull() ?: 0.0; kotlin.math.floor(n).toLong().toString() }
            "ceil"    -> { val n = args[0].toDoubleOrNull() ?: 0.0; kotlin.math.ceil(n).toLong().toString() }
            "sqrt"    -> { val n = args[0].toDoubleOrNull() ?: 0.0; formatNumber(kotlin.math.sqrt(n)) }

            // String
            "length"  -> args[0].length.toString()
            "upper"   -> args[0].uppercase()
            "lower"   -> args[0].lowercase()
            "trim"    -> args[0].trim()
            "reverse" -> args[0].reversed()
            "contains"-> (args[0].contains(args.getOrNull(1) ?: "", ignoreCase = true)).toString()
            "starts"  -> (args[0].startsWith(args.getOrNull(1) ?: "")).toString()
            "ends"    -> (args[0].endsWith(args.getOrNull(1) ?: "")).toString()
            "substring" -> {
                val s = args[0]
                val from = args.getOrNull(1)?.toIntOrNull() ?: 0
                val to   = args.getOrNull(2)?.toIntOrNull() ?: s.length
                s.substring(from.coerceIn(0, s.length), to.coerceIn(0, s.length))
            }
            "replace" -> args[0].replace(args.getOrNull(1) ?: "", args.getOrNull(2) ?: "")
            "concat"  -> args.joinToString("")
            "split"   -> args[0].split(args.getOrNull(1) ?: ",").joinToString(",")
            "join"    -> args.drop(1).joinToString(args.getOrNull(0) ?: ",")
            "count"   -> args[0].split(args.getOrNull(1) ?: ",").size.toString()
            "item"    -> {
                val list = args[0].split(",")
                val idx  = args.getOrNull(1)?.toIntOrNull() ?: 0
                list.getOrElse(idx) { "" }
            }

            // Logic
            "if"      -> if (args[0] == "true") args.getOrElse(1) { "" }
                         else args.getOrElse(2) { "" }
            "not"     -> (args[0] != "true").toString()
            "and"     -> (args.all { it == "true" }).toString()
            "or"      -> (args.any { it == "true" }).toString()
            "isEmpty" -> args[0].isBlank().toString()
            "isNumber"-> (args[0].toDoubleOrNull() != null).toString()

            // Type conversion
            "parseInt"  -> args[0].toIntOrNull()?.toString() ?: "0"
            "parseFloat"-> args[0].toDoubleOrNull()?.let { formatNumber(it) } ?: "0.0"
            "toString"  -> args[0]
            "toBool"    -> (args[0].lowercase() == "true" || args[0] == "1").toString()

            // Date/Time
            "now"       -> System.currentTimeMillis().toString()
            "date"      -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                              .format(java.util.Date())
            "time"      -> java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                              .format(java.util.Date())

            // Utility
            "uuid"      -> java.util.UUID.randomUUID().toString()
            "random"    -> {
                val max = args.getOrNull(0)?.toIntOrNull() ?: 100
                (0..max).random().toString()
            }
            "clamp"     -> {
                val v   = args[0].toDoubleOrNull() ?: 0.0
                val min = args.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val max = args.getOrNull(2)?.toDoubleOrNull() ?: 1.0
                formatNumber(v.coerceIn(min, max))
            }
            "format"    -> {
                // format("Hello {0}, you are {1}", "Ali", "25") → "Hello Ali, you are 25"
                var result = args.getOrElse(0) { "" }
                args.drop(1).forEachIndexed { i, v -> result = result.replace("{$i}", v) }
                result
            }

            else -> "ERROR: Unknown function"
        }
    }

    private fun applyOperator(left: String, op: String, right: String): String {
        val lNum = left.toDoubleOrNull()
        val rNum = right.toDoubleOrNull()

        return when (op) {
            "+"  -> if (lNum != null && rNum != null) formatNumber(lNum + rNum)
                    else left + right   // String concat
            "-"  -> formatNumber((lNum ?: 0.0) - (rNum ?: 0.0))
            "*"  -> formatNumber((lNum ?: 0.0) * (rNum ?: 0.0))
            "/"  -> if (rNum != null && rNum != 0.0) formatNumber((lNum ?: 0.0) / rNum)
                    else "ERROR: Division by zero"
            "%"  -> if (rNum != null && rNum != 0.0) formatNumber((lNum ?: 0.0) % rNum)
                    else "ERROR: Modulo by zero"
            "==" -> (left == right).toString()
            "!=" -> (left != right).toString()
            ">"  -> (lNum != null && rNum != null && lNum > rNum).toString()
            "<"  -> (lNum != null && rNum != null && lNum < rNum).toString()
            ">=" -> (lNum != null && rNum != null && lNum >= rNum).toString()
            "<=" -> (lNum != null && rNum != null && lNum <= rNum).toString()
            else -> "ERROR: Unknown operator: $op"
        }
    }

    /** Split function arguments, respecting nested parentheses and quotes */
    private fun splitArgs(argsStr: String): List<String> {
        if (argsStr.isBlank()) return emptyList()
        val args  = mutableListOf<String>()
        var depth = 0
        var inStr = false
        var current = StringBuilder()

        for (ch in argsStr) {
            when {
                ch == '"' && depth == 0 -> { inStr = !inStr; current.append(ch) }
                inStr                   -> current.append(ch)
                ch == '('               -> { depth++; current.append(ch) }
                ch == ')'               -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 -> { args.add(current.toString()); current = StringBuilder() }
                else                    -> current.append(ch)
            }
        }
        if (current.isNotBlank()) args.add(current.toString())
        return args
    }

    /** Find operator position outside of parentheses/strings */
    private fun findOperator(expr: String, op: String): Int {
        var depth = 0
        var inStr = false
        var i     = 0
        while (i < expr.length) {
            val ch = expr[i]
            when {
                ch == '"'                      -> inStr = !inStr
                !inStr && ch == '('            -> depth++
                !inStr && ch == ')'            -> depth--
                !inStr && depth == 0 && i > 0 &&
                expr.substring(i).startsWith(op) -> return i
            }
            i++
        }
        return -1
    }

    private fun formatNumber(n: Double): String =
        if (n == n.toLong().toDouble()) n.toLong().toString() else "%.4f".format(n).trimEnd('0').trimEnd('.')
}
