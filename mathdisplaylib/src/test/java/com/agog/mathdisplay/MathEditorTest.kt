package com.agog.mathdisplay

import com.agog.mathdisplay.edit.MTMathEditor
import com.agog.mathdisplay.edit.MTMathListIndex
import com.agog.mathdisplay.edit.MTMathListIndex.SubIndexType
import com.agog.mathdisplay.parse.MTFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MTMathEditor: what a math keyboard and the formula gallery do to a formula (the SwiftMath fork's MathEditorTests). */
class MathEditorTest {
    private fun editor(latex: String = ""): MTMathEditor = MTMathEditor.create(latex).also { assertNotNull(latex, it) }!!

    private fun MTMathEditor.type(vararg keys: String) = keys.forEach { insert(it) }

    @Test
    fun typingCharactersAndCommands() {
        val e = editor()
        e.type("x", "+", "1", "\\le", "3")
        assertEquals("x+1\\leq 3", e.latex)
        assertFalse(e.isEmpty)
    }

    @Test
    fun unicodeKeysBecomeCommands() {
        val e = editor()
        e.type("∠", "A", "=", "9", "0", "°")
        assertTrue(e.latex, e.latex.startsWith("\\angle A=90"))
    }

    @Test
    fun exponentThenOut() {
        val e = editor()
        e.type("x", "^", "2")
        assertEquals(SubIndexType.SUPERSCRIPT, e.caret.innermostType)
        e.moveRight() // out of the exponent
        e.type("+", "1")
        assertEquals("x^{2}+1", e.latex)
    }

    @Test
    fun caretPressedAgainLeavesTheExponent() {
        val e = editor()
        e.type("x", "^", "2", "^", "y")
        assertEquals("x^{2}y", e.latex)
    }

    @Test
    fun slashMakesAFractionOfTheNumberBefore() {
        val e = editor()
        e.type("1", "2", "/", "5")
        assertEquals("\\frac{12}{5}", e.latex)
        val empty = editor()
        empty.type("/")
        assertEquals("nothing before: start in the numerator", SubIndexType.NUMERATOR, empty.caret.innermostType)
        empty.type("a")
        empty.moveRight() // to the denominator's slot
        empty.type("b")
        assertEquals("\\frac{a}{b}", empty.latex)
    }

    @Test
    fun aRelationInADenominatorGoesAfterTheFraction() {
        val e = editor()
        e.type("1", "/", "2", "=", "x")
        assertEquals("\\frac{1}{2}=x", e.latex)
    }

    @Test
    fun roots() {
        val e = editor()
        e.type("\\sqrt", "2")
        assertEquals("\\sqrt{2}", e.latex)
        val n = editor()
        n.type("\\nthroot", "3")
        n.moveRight()
        n.type("8")
        assertEquals("\\sqrt[3]{8}", n.latex)
    }

    @Test
    fun templatesGoToTheirFirstSlotAndTakeTheTermBefore() {
        val e = editor()
        e.type("x")
        assertTrue(e.insertTemplate("\\frac{#@}{#?}"))
        assertEquals(SubIndexType.DENOMINATOR, e.caret.innermostType)
        e.type("2")
        assertEquals("\\frac{x}{2}", e.latex)

        val power = editor()
        power.type("a", "b")
        power.insertTemplate("#@^{#?}")
        power.type("3")
        // {ab} is flattened: the exponent goes on b, as when typing ab then ^.
        assertEquals("ab^{3}", power.latex)
    }

    @Test
    fun geometryTemplatesAreEditableInside() {
        val angle = editor()
        angle.insertTemplate("\\widehat{#?}")
        assertEquals(SubIndexType.INNER_LIST, angle.caret.innermostType)
        angle.type("A", "B", "C")
        assertEquals("\\widehat{ABC}", angle.latex)

        val arc = editor()
        arc.insertTemplate("\\overset{\\frown}{#?}")
        arc.type("A", "B")
        assertEquals("an arc in the form KaTeX reads", "\\overset{\\frown}{AB}", arc.latex)

        val vector = editor()
        vector.insertTemplate("\\overrightarrow{#?}")
        vector.type("A", "B")
        assertEquals("\\overrightarrow{AB}", vector.latex)

        val segment = editor()
        segment.insertTemplate("\\overline{#?}")
        segment.type("A", "B")
        assertEquals("\\overline{AB}", segment.latex)
    }

    @Test
    fun backspaceEntersStructuresAndRemovesThemOnlyWhenEmpty() {
        val e = editor("\\frac{1}{2}")
        e.deleteBackward() // into the denominator
        assertEquals(SubIndexType.DENOMINATOR, e.caret.innermostType)
        assertEquals("nothing deleted yet", "\\frac{1}{2}", e.latex)
        e.deleteBackward() // the 2
        e.deleteBackward() // at the denominator's start: back to the numerator
        assertEquals(SubIndexType.NUMERATOR, e.caret.innermostType)
        e.deleteBackward() // the 1
        e.deleteBackward() // an empty fraction goes
        assertEquals("", e.latex)
        assertTrue(e.isEmpty)
    }

    @Test
    fun backspaceInAnEmptyExponentRemovesIt() {
        val e = editor("x^{2}")
        e.deleteBackward() // into the exponent
        e.deleteBackward() // the 2
        e.deleteBackward() // the empty exponent goes
        assertEquals("x", e.latex)
        e.type("+")
        assertEquals("the caret is after x", "x+", e.latex)
    }

    @Test
    fun caretWalksThroughEveryPart() {
        val e = editor("\\frac{a}{b}")
        e.moveToEnd()
        val seen = mutableListOf<SubIndexType>()
        repeat(10) {
            e.moveLeft()
            seen.add(e.caret.innermostType)
        }
        assertTrue(seen.toString(), seen.contains(SubIndexType.NUMERATOR) && seen.contains(SubIndexType.DENOMINATOR))
        assertEquals("stops at the start", MTMathListIndex(0), e.caret)
    }

    @Test
    fun loadingKeepsTheFormula() {
        for (latex in listOf("\\frac{a}{b}+\\sqrt{x}", "x^{2}+y_{1}", "\\widehat{ABC}=60^{\\circ }", "\\overset{\\frown}{AB}")) {
            assertEquals(latex, editor(latex).latex)
        }
        assertEquals("\\frac{1}{2}", editor("\\dfrac{1}{2}").latex)
        assertEquals("(x+1)^{2}", editor("\\left(x+1\\right)^{2}").latex)
    }

    @Test
    fun tablesAreEditedAsLatex() {
        assertNull(MTMathEditor.create("\\begin{cases}x=1\\\\y=2\\end{cases}"))
        assertNull(MTMathEditor.create("\\frac{"))
    }

    @Test
    fun theSlotAtTheCaretIsDrawnBlack() {
        val e = editor()
        e.insertTemplate("\\frac{#?}{#?}")
        val fraction = e.mathList.atoms.first() as MTFraction
        assertEquals("■", fraction.numerator!!.atoms.first().nucleus)
        assertEquals("□", fraction.denominator!!.atoms.first().nucleus)
    }
}
