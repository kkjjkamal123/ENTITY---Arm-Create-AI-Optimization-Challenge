package com.example.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Latex's scanning and Unicode mapping are pure, so they are tested here without an
// emulator. The span path (fractions, radicals) needs a Canvas and is not covered.
class LatexTest {

    private fun bodies(s: String) = Latex.regions(s).map { it.body }
    private fun uni(s: String) = Latex.toUnicode(s)

    // ---- delimiters ----------------------------------------------------------

    @Test
    fun findsAllFourDelimiterForms() {
        assertEquals(listOf("x^2"), bodies("inline \$x^2\$ here"))
        assertEquals(listOf("x^2"), bodies("display \$\$x^2\$\$ here"))
        assertEquals(listOf("x^2"), bodies("paren \\(x^2\\) here"))
        assertEquals(listOf("x^2"), bodies("bracket \\[x^2\\] here"))
    }

    @Test
    fun marksDisplayMathOnly() {
        assertEquals(listOf(false), Latex.regions("\$a\$").map { it.display })
        assertEquals(listOf(true), Latex.regions("\$\$a\$\$").map { it.display })
        assertEquals(listOf(false), Latex.regions("\\(a\\)").map { it.display })
        assertEquals(listOf(true), Latex.regions("\\[a\\]").map { it.display })
    }

    @Test
    fun currencyIsNotMath() {
        assertEquals(emptyList<String>(), bodies("it costs \$5 and \$10 total"))
        assertEquals(emptyList<String>(), bodies("\$1,250.00 and \$3.50"))
    }

    @Test
    fun spaceAdjacentDollarsAreNotMath() {
        assertEquals(emptyList<String>(), bodies("a \$ b \$ c"))
    }

    @Test
    fun blankLineTerminatesInlineMath() {
        assertEquals(emptyList<String>(), bodies("\$a\n\nb\$"))
    }

    @Test
    fun regionsAreNonOverlappingAndInOrder() {
        val r = Latex.regions("\$a\$ text \$b\$")
        assertEquals(listOf("a", "b"), r.map { it.body })
        assertTrue(r[0].end <= r[1].start)
    }

    @Test
    fun unterminatedMathIsIgnored() {
        assertEquals(emptyList<String>(), bodies("an open \$x + 1 with no close"))
    }

    // ---- symbols -------------------------------------------------------------

    @Test
    fun mapsGreekAndOperators() {
        assertEquals("α × β", uni("\\alpha \\times \\beta"))
        assertEquals("θ ≤ π", uni("\\theta \\leq \\pi"))
        assertEquals("∑ ∫ ∞", uni("\\sum \\int \\infty"))
        assertEquals("Δ ⇒ Ω", uni("\\Delta \\Rightarrow \\Omega"))
    }

    @Test
    fun stripsLayoutOnlyMacros() {
        assertEquals("(x)", uni("\\left(x\\right)"))
        assertEquals("ab", uni("a\\quad b").orEmpty().replace(" ", ""))
    }

    @Test
    fun unwrapsTextMacros() {
        assertEquals("hello", uni("\\text{hello}"))
        assertEquals("d", uni("\\mathrm{d}"))
    }

    // ---- scripts -------------------------------------------------------------

    @Test
    fun mapsSuperscriptsAndSubscripts() {
        assertEquals("x²", uni("x^2"))
        assertEquals("y₁", uni("y_1"))
        assertEquals("x²+y₁", uni("x^2+y_1"))
        assertEquals("aⁿ", uni("a^{n}"))
        assertEquals("H₂O", uni("H_2O"))
    }

    @Test
    fun mapsMultiCharacterScriptGroups() {
        assertEquals("x⁽²⁺¹⁾", uni("x^{(2+1)}"))
        assertEquals("aᵢⱼ", uni("a_{ij}"))
    }

    @Test
    fun returnsNullWhenScriptHasNoUnicodeForm() {
        // Capital Q has no superscript codepoint, so the span path must take over.
        assertNull(Latex.script("Q", true))
        assertNull(Latex.script("q", false))
    }

    // ---- layout constructs fall through to the span path ---------------------

    @Test
    fun fracAndSqrtAreNotUnicodeRenderable() {
        assertNull(uni("\\frac{a}{b}"))
        assertNull(uni("\\sqrt{x}"))
        assertNull(uni("1 + \\frac{1}{2}"))
    }

    @Test
    fun unknownMacroFallsThrough() {
        assertNull(uni("\\begin{matrix}"))
    }

    // ---- group parsing -------------------------------------------------------

    @Test
    fun parsesNestedBraceGroups() {
        // "{a{b}c}x": the closing brace is index 6, so the group ends at 7.
        assertEquals("a{b}c" to 7, Latex.groupAt("{a{b}c}x", 0))
    }

    @Test
    fun parsesBareTokenAndMacroGroups() {
        assertEquals("2" to 1, Latex.groupAt("2x", 0))
        assertEquals("\\pi" to 3, Latex.groupAt("\\pi y", 0))
    }

    @Test
    fun unbalancedBraceReturnsNull() {
        assertNull(Latex.groupAt("{a", 0))
    }

    // ---- realistic model output ---------------------------------------------

    @Test
    fun handlesTypicalModelSentence() {
        val src = "The area is \$\\pi r^2\$ and the mean is \$\\frac{1}{n}\\sum x_i\$."
        val r = Latex.regions(src)
        assertEquals(2, r.size)
        // the source is "\pi r^2", so the space between the macro and r survives
        assertEquals("π r²", uni(r[0].body))
        // second needs layout, so Unicode declines and the span path renders it
        assertNull(uni(r[1].body))
    }
}
