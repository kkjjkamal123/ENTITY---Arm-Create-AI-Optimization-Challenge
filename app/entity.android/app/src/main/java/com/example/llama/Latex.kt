package com.example.llama

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.Layout

// LaTeX -> Spanned, in the same hand-rolled spirit as Markdown. No dependency: models
// emit a small, predictable slice of LaTeX and that slice maps onto Unicode plus two
// custom spans (stacked fractions, radicals with a real vinculum).
//
// Splitting matters: math must be lifted out BEFORE Markdown's inline pass, or the * in
// a \times expansion and the _ in x_1 get eaten as emphasis.
object Latex {

    private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    // A math region found in a source string. [display] is $$..$$ / \[..\]: its own
    // centred line rather than inline with the prose.
    data class Region(val start: Int, val end: Int, val body: String, val display: Boolean)

    // ---- pure scanning (JVM-testable, no Android types) ----------------------

    // Math regions in source order, non-overlapping. Recognises $$..$$, \[..\], \(..\)
    // and $..$. Fenced code and inline code are the caller's problem - Markdown strips
    // those first - but currency is handled here: "$5 and $10" must not become math, so
    // a $..$ pair needs non-space just inside both delimiters and no blank line between.
    fun regions(src: String): List<Region> {
        val out = ArrayList<Region>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '\\' && i + 1 < src.length) {
                val open = src[i + 1]
                val close = if (open == '[') "\\]" else if (open == '(') "\\)" else null
                if (close != null) {
                    val e = src.indexOf(close, i + 2)
                    if (e > 0) {
                        out.add(Region(i, e + 2, src.substring(i + 2, e), open == '['))
                        i = e + 2
                        continue
                    }
                }
                i += 2
                continue
            }
            if (c == '$') {
                val dd = src.startsWith("$$", i)
                val delim = if (dd) "$$" else "$"
                val from = i + delim.length
                val e = src.indexOf(delim, from)
                if (e > from && (dd || isInlineMath(src, from, e))) {
                    out.add(Region(i, e + delim.length, src.substring(from, e), dd))
                    i = e + delim.length
                    continue
                }
            }
            i++
        }
        return out
    }

    // "$12 and $15" is currency, "$x+1$" is math. Require non-space adjacency on both
    // sides, reject a blank line inside, and reject a body that is purely a money amount.
    private fun isInlineMath(src: String, from: Int, end: Int): Boolean {
        if (end <= from) return false
        if (src[from].isWhitespace() || src[end - 1].isWhitespace()) return false
        val body = src.substring(from, end)
        if (body.contains("\n\n")) return false
        if (body.all { it.isDigit() || it == ',' || it == '.' }) return false
        return true
    }

    // LaTeX with no structural layout -> a plain Unicode string. Handles symbols,
    // \text/\mathrm wrappers, \left \right, spacing macros and super/subscripts that
    // have Unicode forms. Returns null when the fragment needs real layout (\frac,
    // \sqrt) or a super/subscript with no Unicode equivalent - the span path takes over.
    fun toUnicode(src: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\\' -> {
                    val name = macroAt(src, i + 1) ?: run { i++; return@run "" }
                    if (name.isEmpty()) { i++; continue }
                    if (name == "frac" || name == "sqrt" || name == "dfrac" || name == "tfrac") return null
                    val sym = SYMBOLS[name]
                    when {
                        sym != null -> sb.append(sym)
                        name in STRIP -> {}
                        name in WRAPPERS -> {
                            val g = groupAt(src, i + 1 + name.length) ?: return null
                            sb.append(toUnicode(g.first) ?: return null)
                            i = g.second
                            continue
                        }
                        else -> return null
                    }
                    i += 1 + name.length
                }
                c == '^' || c == '_' -> {
                    val g = groupAt(src, i + 1) ?: return null
                    val mapped = script(g.first, c == '^') ?: return null
                    sb.append(mapped)
                    i = g.second
                }
                c == '{' || c == '}' -> i++
                c == '&' -> { sb.append(' '); i++ }
                c == '~' -> { sb.append(' '); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    // The macro name starting at [at] (letters only), or null.
    private fun macroAt(src: String, at: Int): String? {
        var j = at
        while (j < src.length && src[j].isLetter()) j++
        return if (j > at) src.substring(at, j) else null
    }

    // The braced group (or single token) starting at [at]. Returns body and the index
    // just past it. Handles nesting.
    fun groupAt(src: String, at: Int): Pair<String, Int>? {
        if (at >= src.length) return null
        if (src[at] != '{') {
            // \sqrt2, x^2: a single character, or a whole macro like \pi.
            if (src[at] == '\\') {
                val n = macroAt(src, at + 1) ?: return null
                return src.substring(at, at + 1 + n.length) to (at + 1 + n.length)
            }
            return src[at].toString() to (at + 1)
        }
        var depth = 0
        var j = at
        while (j < src.length) {
            if (src[j] == '{') depth++
            else if (src[j] == '}') {
                depth--
                if (depth == 0) return src.substring(at + 1, j) to (j + 1)
            }
            j++
        }
        return null
    }

    // Unicode super/subscript for a whole group, or null if any character lacks one.
    fun script(body: String, sup: Boolean): String? {
        val flat = toUnicode(body) ?: return null
        val map = if (sup) SUPERSCRIPT else SUBSCRIPT
        val sb = StringBuilder(flat.length)
        for (ch in flat) sb.append(map[ch] ?: return null)
        return sb.toString()
    }

    // ---- span rendering ------------------------------------------------------

    // Render one math body into [out]. [ink] is the text colour, [display] centres it.
    fun append(out: SpannableStringBuilder, body: String, display: Boolean, ink: Int) {
        val start = out.length
        emit(out, body.trim(), ink)
        if (display) {
            out.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, out.length, SPAN)
        }
    }

    // Walks the fragment, taking the Unicode path where it works and dropping to a
    // custom span only for the constructs that genuinely need two-dimensional layout.
    private fun emit(out: SpannableStringBuilder, src: String, ink: Int) {
        var i = 0
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out.append(toUnicode(plain.toString()) ?: plain.toString())
                plain.setLength(0)
            }
        }
        while (i < src.length) {
            val c = src[i]
            if (c == '\\') {
                val name = macroAt(src, i + 1)
                if (name == "frac" || name == "dfrac" || name == "tfrac") {
                    val n = groupAt(src, i + 1 + name.length)
                    val d = n?.let { groupAt(src, it.second) }
                    if (n != null && d != null) {
                        flush()
                        appendFraction(out, n.first, d.first, ink)
                        i = d.second
                        continue
                    }
                } else if (name == "sqrt") {
                    var at = i + 1 + name.length
                    var index: String? = null
                    if (at < src.length && src[at] == '[') {
                        val close = src.indexOf(']', at)
                        if (close > at) { index = src.substring(at + 1, close); at = close + 1 }
                    }
                    val g = groupAt(src, at)
                    if (g != null) {
                        flush()
                        appendRadical(out, g.first, index, ink)
                        i = g.second
                        continue
                    }
                } else if (name == "\\" || (name == null && i + 1 < src.length && src[i + 1] == '\\')) {
                    flush()
                    out.append('\n')
                    i += 2
                    continue
                }
            }
            if (c == '^' || c == '_') {
                val g = groupAt(src, i + 1)
                if (g != null) {
                    val uni = script(g.first, c == '^')
                    if (uni != null) {
                        plain.append(uni)
                    } else {
                        flush()
                        val s = out.length
                        emit(out, g.first, ink)
                        out.setSpan(if (c == '^') SuperscriptSpan() else SubscriptSpan(), s, out.length, SPAN)
                        out.setSpan(RelativeSizeSpan(0.72f), s, out.length, SPAN)
                    }
                    i = g.second
                    continue
                }
            }
            plain.append(c)
            i++
        }
        flush()
    }

    private fun appendFraction(out: SpannableStringBuilder, num: String, den: String, ink: Int) {
        val n = flatten(num)
        val d = flatten(den)
        // Nested layout inside a fraction is where hand-rolling stops paying: fall back
        // to the linear form rather than growing a box model.
        if (n == null || d == null) {
            out.append("(").append(flatten(num) ?: num).append(")/(").append(flatten(den) ?: den).append(")")
            return
        }
        val at = out.length
        out.append(' ')
        out.setSpan(FractionSpan(n, d, ink), at, out.length, SPAN)
    }

    private fun appendRadical(out: SpannableStringBuilder, body: String, index: String?, ink: Int) {
        val b = flatten(body)
        if (b == null) {
            out.append('√').append('(').append(flatten(body) ?: body).append(')')
            return
        }
        val at = out.length
        out.append(' ')
        out.setSpan(RadicalSpan(b, index?.let { flatten(it) }, ink), at, out.length, SPAN)
    }

    // A fragment reduced to a single line of text, or null if it needs layout itself.
    private fun flatten(src: String): String? = toUnicode(src)

    // Numerator over denominator with a real rule between them. Measured so the line
    // box grows upward, keeping the surrounding paragraph from overlapping it.
    private class FractionSpan(
        private val num: String,
        private val den: String,
        private val ink: Int,
    ) : ReplacementSpan() {

        private fun scaled(paint: Paint) = Paint(paint).apply { textSize = paint.textSize * 0.82f }

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            val p = scaled(paint)
            val w = maxOf(p.measureText(num), p.measureText(den))
            val pad = paint.textSize * 0.18f
            if (fm != null) {
                val h = p.fontMetrics
                val line = h.descent - h.ascent
                fm.ascent = (-line - pad).toInt()
                fm.top = fm.ascent
                fm.descent = (line * 0.55f).toInt()
                fm.bottom = fm.descent
            }
            return (w + pad * 2).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val p = scaled(paint)
            p.color = ink
            val w = maxOf(p.measureText(num), p.measureText(den))
            val pad = paint.textSize * 0.18f
            val cx = x + pad + w / 2f
            val rule = y - paint.textSize * 0.28f
            canvas.drawText(num, cx - p.measureText(num) / 2f, rule - paint.textSize * 0.12f, p)
            canvas.drawText(den, cx - p.measureText(den) / 2f, rule + p.textSize * 0.92f, p)
            val stroke = Paint(p).apply {
                color = ink
                strokeWidth = maxOf(1f, paint.textSize * 0.05f)
            }
            canvas.drawLine(x + pad * 0.5f, rule, x + pad * 0.5f + w + pad, rule, stroke)
        }
    }

    // Radical sign plus a vinculum drawn over the full width of the radicand, which is
    // what distinguishes it from the plain "√(x+1)" fallback.
    private class RadicalSpan(
        private val body: String,
        private val index: String?,
        private val ink: Int,
    ) : ReplacementSpan() {

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            val hook = paint.textSize * 0.62f
            if (fm != null) {
                val h = paint.fontMetrics
                fm.ascent = (h.ascent - paint.textSize * 0.18f).toInt()
                fm.top = fm.ascent
                fm.descent = h.descent.toInt()
                fm.bottom = fm.descent
            }
            return (hook + paint.measureText(body) + paint.textSize * 0.2f).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val p = Paint(paint).apply { color = ink }
            val hook = paint.textSize * 0.62f
            val w = p.measureText(body)
            val barY = y - paint.textSize * 0.82f
            val stroke = Paint(p).apply { strokeWidth = maxOf(1f, paint.textSize * 0.055f) }
            // The tick, the diagonal, then the bar across the top of the radicand.
            canvas.drawLine(x, y - paint.textSize * 0.32f, x + hook * 0.3f, y - paint.textSize * 0.22f, stroke)
            canvas.drawLine(x + hook * 0.3f, y - paint.textSize * 0.22f, x + hook * 0.58f, y + p.fontMetrics.descent * 0.4f, stroke)
            canvas.drawLine(x + hook * 0.58f, y + p.fontMetrics.descent * 0.4f, x + hook * 0.82f, barY, stroke)
            canvas.drawLine(x + hook * 0.82f, barY, x + hook + w + paint.textSize * 0.12f, barY, stroke)
            canvas.drawText(body, x + hook, y.toFloat(), p)
            if (index != null) {
                val ip = Paint(p).apply { textSize = paint.textSize * 0.55f }
                canvas.drawText(index, x + hook * 0.1f, y - paint.textSize * 0.52f, ip)
            }
        }
    }

    // ---- tables --------------------------------------------------------------

    // Macros that carry no glyph and no grouping: pure spacing / layout hints.
    private val STRIP = setOf(
        "left", "right", "big", "Big", "bigg", "Bigg", "quad", "qquad", "limits",
        "displaystyle", "textstyle", "nolimits", "!", ",", ";", ":",
    )

    // Macros whose only job is to wrap a group we render as-is.
    private val WRAPPERS = setOf("text", "mathrm", "mathbf", "mathit", "operatorname", "mathsf")

    private val SUPERSCRIPT = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
        'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ',
        'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ',
        'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
        'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
    )

    private val SUBSCRIPT = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
        'v' to 'ᵥ', 'x' to 'ₓ',
    )

    private val SYMBOLS = mapOf(
        // greek
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ",
        "upsilon" to "υ", "phi" to "φ", "varphi" to "ϕ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
        "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        // operators and relations
        "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "cdot" to "·", "ast" to "∗", "star" to "⋆",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡",
        "sim" to "∼", "simeq" to "≃", "propto" to "∝", "ll" to "≪",
        "gg" to "≫",
        // big operators
        "sum" to "∑", "prod" to "∏", "int" to "∫", "iint" to "∬",
        "oint" to "∮", "coprod" to "∐",
        // sets and logic
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆",
        "supset" to "⊃", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩",
        "emptyset" to "∅", "varnothing" to "∅", "forall" to "∀",
        "exists" to "∃", "nexists" to "∄", "neg" to "¬", "lnot" to "¬",
        "land" to "∧", "lor" to "∨", "therefore" to "∴", "because" to "∵",
        // arrows
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "leftrightarrow" to "↔",
        "Leftrightarrow" to "⇔", "mapsto" to "↦", "uparrow" to "↑",
        "downarrow" to "↓",
        // calculus and misc
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "prime" to "′",
        "circ" to "∘", "degree" to "°", "angle" to "∠", "perp" to "⊥",
        "parallel" to "∥", "cdots" to "⋯", "ldots" to "…", "dots" to "…",
        "vdots" to "⋮", "ddots" to "⋱", "aleph" to "ℵ", "hbar" to "ℏ",
        "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "surd" to "√",
        // named functions render as upright words
        "sin" to "sin", "cos" to "cos", "tan" to "tan", "log" to "log", "ln" to "ln",
        "exp" to "exp", "lim" to "lim", "max" to "max", "min" to "min", "det" to "det",
        "gcd" to "gcd", "sec" to "sec", "csc" to "csc", "cot" to "cot", "arcsin" to "arcsin",
        "arccos" to "arccos", "arctan" to "arctan", "sinh" to "sinh", "cosh" to "cosh",
        "tanh" to "tanh",
    )
}
