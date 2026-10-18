package com.agog.mathdisplay

import com.agog.mathdisplay.parse.MTAccent
import com.agog.mathdisplay.parse.MTFraction
import com.agog.mathdisplay.parse.MTInner
import com.agog.mathdisplay.parse.MTMathAtomType
import com.agog.mathdisplay.parse.MTMathList
import com.agog.mathdisplay.parse.MTMathListBuilder
import com.agog.mathdisplay.parse.MTMathStyle
import com.agog.mathdisplay.parse.MTParseError
import com.agog.mathdisplay.parse.MTParseErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notation of Vietnamese secondary-school math (MathMate), as the SwiftMath fork reads it. */
class TextbookNotationTest {
    private fun parse(latex: String): MTMathList {
        val error = MTParseError()
        val list = MTMathListBuilder.buildFromString(latex, error)
        assertEquals("$latex: ${error.errordesc}", MTParseErrors.ErrorNone, error.errorcode)
        return list!!
    }

    private fun roundTrip(latex: String) = MTMathListBuilder.toLatexString(parse(latex)).trim()

    @Test
    fun arcsAreStretchyAccentsWrittenAsTheWebDoes() {
        for (latex in listOf("\\overset{\\frown}{AB}", "\\stackrel{\\frown}{AB}", "\\overparen{AB}", "\\wideparen{AB}")) {
            val arc = parse(latex).atoms.single() as MTAccent
            assertEquals("⏜", arc.nucleus)
            assertTrue(arc.isWide)
            assertEquals(2, arc.innerList?.atoms?.size)
            assertEquals("\\overset{\\frown}{AB}", roundTrip(latex))
        }
        val error = MTParseError()
        assertNull(MTMathListBuilder.buildFromString("\\overset{x}{AB}", error))
        assertEquals(MTParseErrors.InvalidCommand, error.errorcode)
    }

    @Test
    fun accentsKeepTheCommandTheyWereWrittenWith() {
        assertEquals("\\widehat{ABC}", roundTrip("\\widehat{ABC}"))
        assertEquals("\\hat{A}", roundTrip("\\hat{A}"))
        assertEquals("\\overrightarrow{AB}", roundTrip("\\overrightarrow{AB}"))
        assertEquals("\\vec{u}", roundTrip("\\vec{u}"))
        assertTrue((parse("\\overrightarrow{AB}").atoms.single() as MTAccent).isStretchyArrow)
        assertTrue((parse("\\widehat{ABC}").atoms.single() as MTAccent).isWide)
    }

    @Test
    fun similarTrianglesAndSymbols() {
        val list = parse("\\triangle ABC\\backsim\\triangle DEF")
        assertEquals("∽", list.atoms[4].nucleus)
        assertEquals(MTMathAtomType.KMTMathAtomRelation, list.atoms[4].type)
        assertEquals("∅", parse("\\varnothing").atoms.single().nucleus)
        assertEquals("⟹", parse("\\implies").atoms.single().nucleus)
        assertEquals("⩽", parse("\\leqslant").atoms.single().nucleus)
        assertEquals("⩾", parse("\\geqslant").atoms.single().nucleus)
        assertEquals("…", parse("1,2,\\dots").atoms.last().nucleus)
    }

    @Test
    fun displayAndTextFractions() {
        val frac = parse("\\dfrac{1}{2}").atoms.single() as MTFraction
        assertTrue(frac.numerator!!.atoms.first() is MTMathStyle)
        assertTrue(frac.denominator!!.atoms.first() is MTMathStyle)
        assertNotNull(parse("\\tfrac{a}{b}").atoms.single() as MTFraction)
    }

    @Test
    fun primesAreSuperscripts() {
        val list = parse("f'(x)+g''")
        assertEquals("′", list.atoms[0].superScript!!.atoms.single().nucleus)
        assertEquals(2, list.atoms.last().superScript!!.atoms.size)
        assertEquals("f^{\\prime }(x)+g^{\\prime \\prime }", roundTrip("f'(x)+g''"))
    }

    @Test
    fun vietnameseTextAndUnicodeSymbolsAreKept() {
        val text = parse("\\text{Vì nên đồng dạng}")
        assertEquals("Vì nên đồng dạng", text.atoms.joinToString("") { it.nucleus })
        val symbols = parse("x ≥ 0, ∠A = 90°")
        assertEquals(MTMathAtomType.KMTMathAtomRelation, symbols.atoms[1].type)
        assertEquals("≥", symbols.atoms[1].nucleus)
        assertEquals("∠", symbols.atoms[4].nucleus)
        assertEquals("°", symbols.atoms.last().nucleus)
    }

    @Test
    fun casesWithOneColumn() {
        val inner = parse("\\begin{cases}x+y=3\\\\x-y=1\\end{cases}").atoms.single() as MTInner
        assertEquals("{", inner.leftBoundary?.nucleus)
        parse("\\begin{cases}x & x>0\\\\-x & x\\le 0\\end{cases}")
    }
}
