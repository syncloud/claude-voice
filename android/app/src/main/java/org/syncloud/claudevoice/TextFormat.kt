package org.syncloud.claudevoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.content.ContextCompat

class TextFormat(private val ctx: Context) {

    private fun col(res: Int) = ContextCompat.getColor(ctx, res)

    fun colored(text: String, colorRes: Int, mono: Boolean = false, italic: Boolean = false): SpannableString {
        val s = SpannableString(text)
        s.setSpan(ForegroundColorSpan(col(colorRes)), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (mono) s.setSpan(TypefaceSpan("monospace"), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (italic) s.setSpan(StyleSpan(Typeface.ITALIC), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    fun you(text: String): CharSequence {
        val out = SpannableStringBuilder()
        out.append(colored("you  ", R.color.branch_text))
        out.append("$text\n\n")
        return out
    }

    fun action(label: String): CharSequence = colored("▸ $label\n", R.color.action_text, italic = true)

    fun reply(text: String): CharSequence {
        val out = SpannableStringBuilder()
        val parts = text.split("```")
        for ((i, part) in parts.withIndex()) {
            if (i % 2 == 0) {
                val t = stripInline(part).trim()
                if (t.isNotEmpty()) out.append("$t\n")
            } else {
                var code = part
                val nl = code.indexOf('\n')
                if (nl >= 0) {
                    val first = code.substring(0, nl).trim()
                    if (first.isNotEmpty() && !first.contains(' ') && first.length < 15) {
                        code = code.substring(nl + 1)
                    }
                }
                out.append(codeBlock(code))
            }
        }
        out.append("\n")
        return out
    }

    fun diff(file: String, patch: String): CharSequence {
        val out = SpannableStringBuilder()
        out.append(colored("✎ $file\n", R.color.action_text, italic = true))
        for (line in patch.split("\n")) {
            val color = when {
                line.startsWith("+") -> R.color.diff_add
                line.startsWith("-") -> R.color.diff_del
                else -> R.color.action_text
            }
            out.append(colored("$line\n", color, mono = true))
        }
        out.append("\n")
        return out
    }

    fun fmtTok(n: Int) = if (n >= 1000) "${n / 1000}k" else "$n"

    private fun stripInline(t: String): String {
        var s = t
        s = Regex("`([^`]*)`").replace(s, "$1")
        s = Regex("\\[([^\\]]+)\\]\\([^)]*\\)").replace(s, "$1")
        s = Regex("^\\s{0,3}[-*+]\\s+", RegexOption.MULTILINE).replace(s, "• ")
        s = Regex("(\\*\\*|\\*|__|_|#+|>|~~|~)").replace(s, "")
        return s
    }

    private val codeKeywords = setOf(
        "val", "var", "fun", "def", "class", "interface", "object", "return", "if", "else", "for",
        "while", "do", "when", "switch", "case", "break", "continue", "import", "package", "public",
        "private", "protected", "static", "final", "void", "new", "this", "super", "try", "catch",
        "finally", "throw", "throws", "func", "let", "const", "type", "struct", "enum", "defer",
        "range", "map", "chan", "select", "async", "await", "yield", "lambda", "in", "is", "as",
        "and", "or", "not", "true", "false", "null", "nil", "none", "int", "string", "bool",
        "boolean", "float", "double", "long", "char", "byte", "echo", "print", "println", "with",
        "from", "global", "pass", "raise", "except", "elif", "using", "namespace", "template",
        "unsigned", "virtual", "override", "suspend", "data", "sealed", "companion", "init", "by"
    )

    private fun codeBlock(code: String): CharSequence {
        val body = code.trimEnd('\n') + "\n"
        val sb = SpannableStringBuilder(body)
        val flag = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        sb.setSpan(ForegroundColorSpan(col(R.color.code_text)), 0, sb.length, flag)
        sb.setSpan(TypefaceSpan("monospace"), 0, sb.length, flag)
        sb.setSpan(CodeBlockBg(col(R.color.code_bg)), 0, sb.length, flag)
        highlightInto(sb, body)
        return sb
    }

    private fun highlightInto(sb: SpannableStringBuilder, code: String) {
        val n = code.length
        var i = 0
        fun span(s: Int, e: Int, c: Int) =
            sb.setSpan(ForegroundColorSpan(c), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val kw = col(R.color.code_kw); val str = col(R.color.code_str)
        val com = col(R.color.code_com); val num = col(R.color.code_num)
        while (i < n) {
            val c = code[i]
            when {
                c == '/' && i + 1 < n && code[i + 1] == '/' -> {
                    val s = i; while (i < n && code[i] != '\n') i++; span(s, i, com)
                }
                c == '#' -> { val s = i; while (i < n && code[i] != '\n') i++; span(s, i, com) }
                c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                    val s = i; i += 2
                    while (i + 1 < n && !(code[i] == '*' && code[i + 1] == '/')) i++
                    i = minOf(n, i + 2); span(s, i, com)
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val q = c; val s = i; i++
                    while (i < n && code[i] != q) { if (code[i] == '\\') i++; i++ }
                    i = minOf(n, i + 1); span(s, i, str)
                }
                c.isDigit() -> {
                    val s = i
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '.')) i++
                    span(s, i, num)
                }
                c.isLetter() || c == '_' -> {
                    val s = i
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    if (code.substring(s, i) in codeKeywords) span(s, i, kw)
                }
                else -> i++
            }
        }
    }

    private class CodeBlockBg(private val bg: Int) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int,
            baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int
        ) {
            val orig = paint.color
            paint.color = bg
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            paint.color = orig
        }
    }
}
