@file:Suppress("ConstantConditionIf")

package com.agog.mathdisplay.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import com.agog.mathdisplay.parse.NSNotFound
import com.agog.mathdisplay.parse.NSRange

import com.agog.mathdisplay.parse.*

import com.agog.mathdisplay.render.MTLinePosition.*

const val DEBUG = false

data class CGPoint(var x: Float = 0.0f, var y: Float = 0.0f)
data class CGRect(var x: Float = 0.0f, var y: Float = 0.0f, var width: Float = 0.0f, var height: Float = 0.0f)

open class MTDisplay(open var ascent: Float = 0.0f, open var descent: Float = 0.0f, open var width: Float = 0.0f,
                     var range: NSRange = NSRange(), var hasScript: Boolean = false) {

    var shiftDown: Float = 0.0f
    /// The distance from the axis to the top of the display
    /// The distance from the axis to the bottom of the display
    /// The width of the display

    /// position: Position of the display with respect to the parent view or display.
    //  range: The range of characters supported by this item

    /// Whether the display has a subscript/superscript following it.

    /// The text color for this display

    // The local color, if the color was mutated local with the color
// command

    var position: CGPoint = CGPoint()
        set(value) {
            field = value.copy()
            positionChanged()
        }

    var textColor: Int = Color.BLACK
        set(value) {
            field = value
            colorChanged()
        }

    var localTextColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            colorChanged()
        }

    init {
        this.range = range.copy()
    }

    open fun positionChanged() {

    }

    open fun colorChanged() {
    }


    open fun draw(canvas: Canvas) {
        if (DEBUG) {
            val strokePaint = Paint(Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
            strokePaint.setColor(Color.RED)
            canvas.drawLine(0.0f, -descent, width, ascent, strokePaint)
            strokePaint.setColor(Color.GREEN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                canvas.drawArc(position.x - 2, position.y - 2, position.x + 2, position.y + 2, 0f, 360f, false, strokePaint)
            }
        }

    }

    fun displayBounds(): CGRect {
        return CGRect(position.x, position.y - descent, width, ascent + descent)
    }

}


// List of normal atoms to display that would be an attributed string on iOS
// Since we do not allow kerning attribute changes this is a string displayed using the advances for the font
// Normally this is a single character. In some cases the string will be fused atoms
//
// Characters the math font lacks (Vietnamese letters in \text{…}, CJK…) are drawn with the
// system serif font, as CoreText falls back to a system font on iOS.
class MTCTLineDisplay(val str: String, range: NSRange, val font: MTFont, val atoms: List<MTMathAtom>) :
        MTDisplay(range = range) {

    /** A piece of the string: glyphs of the math font, or text drawn with the fallback font. */
    private class Run(val start: Int, val text: String, val glyphs: List<Int>?, val advances: Array<Float>)

    private val runs: List<Run>

    init {
        runs = makeRuns()
        computeDimensions()
    }

    private fun makeRuns(): List<Run> {
        val out = mutableListOf<Run>()
        var index = 0
        var runStart = 0
        var runIsFallback: Boolean? = null
        fun close(end: Int) {
            val fallback = runIsFallback ?: return
            if (end <= runStart) return
            val text = str.substring(runStart, end)
            if (fallback) {
                val paint = fallbackPaint(font.fontSize)
                // One advance per UTF-16 unit, so string offsets map straight to x.
                val advances = Array(text.length) { 0.0f }
                for (i in text.indices) advances[i] = paint.measureText(text, 0, i + 1) - (if (i > 0) paint.measureText(text, 0, i) else 0.0f)
                out.add(Run(runStart, text, null, advances))
            } else {
                val glyphs = font.getGidListForString(text)
                val advances = Array(glyphs.size) { 0.0f }
                font.mathTable.getAdvancesForGlyphs(glyphs, advances, glyphs.size)
                out.add(Run(runStart, text, glyphs, advances))
            }
        }
        while (index < str.length) {
            val codepoint = str.codePointAt(index)
            val missing = font.mathTable.getGlyphForCodepoint(codepoint) == 0
            if (runIsFallback != missing) {
                close(index)
                runStart = index
                runIsFallback = missing
            }
            index += Character.charCount(codepoint)
        }
        close(str.length)
        return out
    }

    // Our own implementation of the ios6 function to get glyph path bounds.
    fun computeDimensions() {
        this.width = 0.0f
        for (run in runs) {
            val glyphs = run.glyphs
            if (glyphs == null) {
                val bounds = android.graphics.Rect()
                fallbackPaint(font.fontSize).getTextBounds(run.text, 0, run.text.length, bounds)
                this.ascent = maxOf(this.ascent, -bounds.top.toFloat())
                this.descent = maxOf(this.descent, bounds.bottom.toFloat())
                this.width += run.advances.sum()
                continue
            }
            val num = glyphs.count()
            val bboxes: Array<BoundingBox?> = arrayOfNulls(num)
            font.mathTable.getBoundingRectsForGlyphs(glyphs, bboxes, num)
            for (i in 0 until num) {
                val b = bboxes[i]
                if (b != null) {
                    val ascent = maxOf(0.0f, b.upperRightY - 0)
                    // Descent is how much the line goes below the origin. However if the line is all above the origin, then descent can't be negative.
                    val descent = maxOf(0.0f, 0.0f - b.lowerLeftY)
                    if (ascent > this.ascent) {
                        this.ascent = ascent
                    }
                    if (descent > this.descent) {
                        this.descent = descent
                    }
                    this.width += run.advances[i]
                }
            }
        }
    }

    /** The x of a position in `str` (a UTF-16 offset) from the start of the line. */
    fun offsetForStringIndex(index: Int): Float {
        var x = 0.0f
        for (run in runs) {
            if (index <= run.start) break
            val glyphs = run.glyphs
            if (glyphs == null) {
                val count = minOf(index - run.start, run.text.length)
                for (i in 0 until count) x += run.advances[i]
            } else {
                var offset = run.start
                for (i in glyphs.indices) {
                    if (offset >= index) break
                    x += run.advances[i]
                    offset += Character.charCount(str.codePointAt(offset))
                }
            }
        }
        return x
    }

    /** The position in `str` (a UTF-16 offset) nearest to x, measured from the start of the line. */
    fun stringIndexForOffset(x: Float): Int {
        var left = 0.0f
        var index = 0
        while (index < str.length) {
            val next = index + Character.charCount(str.codePointAt(index))
            val right = offsetForStringIndex(next)
            if (x < (left + right) / 2) return index
            left = right
            index = next
        }
        return str.length
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val textPaint = Paint(Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
        textPaint.setColor(textColor)
        val drawer = MTDrawFreeType(font.mathTable)

        canvas.save()
        canvas.translate(position.x, position.y)
        canvas.scale(1.0f, -1.0f)
        var x = 0.0f
        for (run in runs) {
            val glyphs = run.glyphs
            if (glyphs == null) {
                val paint = fallbackPaint(font.fontSize)
                paint.color = textColor
                canvas.drawText(run.text, x, 0.0f, paint)
                x += run.advances.sum()
                continue
            }
            for (i in glyphs.indices) {
                drawer.drawGlyph(canvas, textPaint, glyphs[i], x, 0.0f)
                x += run.advances[i]
            }
        }
        canvas.restore()
    }

    companion object {
        private val fallbackPaints = HashMap<Float, Paint>()

        /** The system serif font (Noto Serif: every Vietnamese letter), at the math font's size. */
        fun fallbackPaint(size: Float): Paint = fallbackPaints.getOrPut(size) {
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = android.graphics.Typeface.SERIF
                textSize = size
            }
        }
    }
}


/**
@typedef MTLinePosition
@brief The type of position for a line, i.e. subscript/superscript or regular.
 */

enum class MTLinePosition {
    /// Regular
    KMTLinePositionRegular,
    /// Positioned at a subscript
    KMTLinePositionSubscript,
    /// Positioned at a superscript
    KMTLinePositionSuperscript
}

class MTMathListDisplay(displays: List<MTDisplay>, range: NSRange) : MTDisplay(range = range) {
    /// Where the line is positioned
    var type: MTLinePosition = KMTLinePositionRegular
    /// An array of MTDisplays which are positioned relative to the position of the
/// the current display.
    var subDisplays: List<MTDisplay>? = null
    /// If a subscript or superscript this denotes the location in the parent MTList. For a
    /// regular list this is NSNotFound
    var index = NSNotFound

    init {
        this.subDisplays = displays
        this.recomputeDimensions()
    }

    override fun colorChanged() {
        val sd = this.subDisplays
        if (sd != null) {
            for (displayAtom in sd.toList()) {
                // set the global color, if there is no local color
                if (displayAtom.localTextColor == Color.TRANSPARENT) {
                    displayAtom.textColor = this.textColor
                } else {
                    displayAtom.textColor = displayAtom.localTextColor
                }

            }
        }
    }


    override fun draw(canvas: Canvas) {
        canvas.save()
        if (DEBUG) {
            val strokePaint = Paint(Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
            strokePaint.setColor(Color.BLACK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                canvas.drawArc(-4f, -4f, 4f, 4f, 4f, 180f, false, strokePaint)
            }
        }

        canvas.translate(position.x, position.y)
        if (DEBUG) {
            val strokePaint = Paint(Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
            strokePaint.setColor(Color.BLUE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                canvas.drawArc(-3f, -3f, 3f, 3f, 0f, 360f, false, strokePaint)
            }
        }
        // draw each atom separately
        val sd = this.subDisplays
        if (sd != null) {
            for (displayAtom in sd.toList()) {
                displayAtom.draw(canvas)
            }
        }
        canvas.restore()
    }


    fun recomputeDimensions() {
        var max_ascent = 0.0f
        var max_descent = 0.0f
        var max_width = 0.0f
        val sd = this.subDisplays
        if (sd != null) {
            for (atom in sd.toList()) {
                val ascent = maxOf(0.0f, atom.position.y + atom.ascent)
                if (ascent > max_ascent) {
                    max_ascent = ascent
                }

                val descent = maxOf(0.0f, 0 - (atom.position.y - atom.descent))
                if (descent > max_descent) {
                    max_descent = descent
                }
                val width = atom.width + atom.position.x
                if (width > max_width) {
                    max_width = width
                }
            }
        }
        this.ascent = max_ascent
        this.descent = max_descent
        this.width = max_width
    }

}

// MTFractionDisplay

class MTFractionDisplay(var numerator: MTMathListDisplay, var denominator: MTMathListDisplay, range: NSRange) :
        MTDisplay(range = range) {
    var linePosition = 0.0f
    var lineThickness = 0.0f


    // NSAssert(self.range.length == 1, @"Fraction range length not 1 - range (%lu, %lu)", (unsigned long)range.location, (unsigned long)range.length)
    var numeratorUp: Float = 0.0f
        set(value) {
            field = value
            this.updateNumeratorPosition()
        }
    var denominatorDown: Float = 0.0f
        set(value) {
            field = value
            this.updateDenominatorPosition()
        }

    override var ascent: Float
        get() = this.numerator.ascent + this.numeratorUp
        set(value) {
        }

    override var descent: Float
        get() = this.denominator.descent + this.denominatorDown
        set(value) {
        }


    override var width: Float
        get() = maxOf(this.numerator.width, this.denominator.width)
        set(value) {
        }


    fun updateDenominatorPosition() {
        this.denominator.position = CGPoint(this.position.x + (this.width - this.denominator.width) / 2, this.position.y - this.denominatorDown)
    }

    fun updateNumeratorPosition() {

        this.numerator.position = CGPoint(this.position.x + (this.width - this.numerator.width) / 2, this.position.y + this.numeratorUp)
    }

    override fun positionChanged() {
        this.updateDenominatorPosition()
        this.updateNumeratorPosition()
    }

    override fun colorChanged() {
        this.numerator.textColor = this.textColor
        this.denominator.textColor = this.textColor
    }

    override fun draw(canvas: Canvas) {
        this.numerator.draw(canvas)
        this.denominator.draw(canvas)

        if (lineThickness != 0f) {
            val strokePaint = Paint(Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
            strokePaint.setColor(textColor)
            strokePaint.strokeWidth = lineThickness
            canvas.drawLine(position.x, position.y + linePosition, position.x + width, position.y + linePosition,
                    strokePaint)
        }
    }

}

// MTRadicalDisplay


class MTRadicalDisplay(val radicand: MTMathListDisplay, val radicalGlyph: MTDisplay, range: NSRange) :
        MTDisplay(range = range) {

    init {
        updateRadicandPosition()
    }

    var radicalShift: Float = 0.0f
    var degree: MTMathListDisplay? = null
    var topKern: Float = 0.0f
    var lineThickness: Float = 0.0f


    fun setDegree(degree: MTMathListDisplay, fontMetrics: MTFontMathTable) {
        // sets up the degree of the radical
        var kernBefore = fontMetrics.radicalKernBeforeDegree
        val kernAfter = fontMetrics.radicalKernAfterDegree
        val raise = fontMetrics.radicalDegreeBottomRaisePercent * (this.ascent - this.descent)

        // The layout is:
        // kernBefore, raise, degree, kernAfter, radical
        this.degree = degree

        // the radical is now shifted by kernBefore + degree.width + kernAfter
        this.radicalShift = kernBefore + degree.width + kernAfter
        if (radicalShift < 0) {
            // we can't have the radical shift backwards, so instead we increase the kernBefore such
            // that _radicalShift will be 0.
            kernBefore -= radicalShift
            radicalShift = 0.0f
        }

        // Note: position of degree is relative to parent.
        val deg = this.degree
        if (deg != null) {
            deg.position = CGPoint(this.position.x + kernBefore, this.position.y + raise)
            // Update the width by the _radicalShift
            this.width = radicalShift + radicalGlyph.width + this.radicand.width
        }
        // update the position of the radicand
        this.updateRadicandPosition()
    }

    override fun positionChanged() {
        updateRadicandPosition()
    }

    fun updateRadicandPosition() {
        // The position of the radicand includes the position of the MTRadicalDisplay
        // This is to make the positioning of the radical consistent with fractions and
        // have the cursor position finding algorithm work correctly.
        // move the radicand by the width of the radical sign
        this.radicand.position = CGPoint(this.position.x + radicalShift + radicalGlyph.width, this.position.y)
    }

    override fun colorChanged() {
        this.radicand.textColor = this.textColor
        this.radicalGlyph.textColor = this.textColor
        val deg = this.degree
        if (deg != null) {
            deg.textColor = this.textColor
        }
    }

    override fun draw(canvas: Canvas) {
        this.radicand.draw(canvas)
        degree?.draw(canvas)


        canvas.save()

        // Make the current position the origin as all the positions of the sub atoms are relative to the origin.
        canvas.translate(position.x + radicalShift, position.y)

        // Draw the glyph.
        radicalGlyph.draw(canvas)

        // Draw the VBOX
        // for the kern of, we don't need to draw anything.
        val heightFromTop = topKern

        // draw the horizontal line with the given thickness
        val strokePaint = Paint(Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
        strokePaint.setColor(textColor)
        strokePaint.strokeWidth = lineThickness
        strokePaint.strokeCap = Paint.Cap.ROUND
        val x = radicalGlyph.width
        val y = ascent - heightFromTop - lineThickness / 2
        canvas.drawLine(x, y, x + radicand.width, y, strokePaint)

        canvas.restore()

    }
}


// MTGlyphDisplay

class MTGlyphDisplay(val glyph: CGGlyph, range: NSRange, val myfont: MTFont) :
        MTDisplay(range = range) {

    /// Stretches the glyph sideways: a wide accent or an arrow longer than the font's largest
    /// variant (\widehat over a long name, \overrightarrow over a long vector).
    var scaleX: Float = 1.0f

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val textPaint = Paint(Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        textPaint.setColor(textColor)
        val drawer = MTDrawFreeType(myfont.mathTable)

        canvas.save()
        canvas.translate(position.x, position.y - this.shiftDown)
        canvas.scale(scaleX, -1.0f)
        drawer.drawGlyph(canvas, textPaint, glyph.gid, 0.0f, 0.0f)
        canvas.restore()
    }

    override var ascent: Float
        get() = super.ascent - this.shiftDown
        set(value) {
            super.ascent = value
        }

    override var descent: Float
        get() = super.descent + this.shiftDown
        set(value) {
            super.descent = value
        }

}


// MTGlyphConstructionDisplay

class MTGlyphConstructionDisplay(val glyphs: MutableList<Int>, val offsets: MutableList<Float>, val myfont: MTFont) :
        MTDisplay() {
    init {
        assert(glyphs.size == offsets.size)
    }


    override fun draw(canvas: Canvas) {

        val drawer = MTDrawFreeType(myfont.mathTable)
        canvas.save()

        // Make the current position the origin as all the positions of the sub atoms are relative to the origin.
        canvas.translate(position.x, position.y - shiftDown)

        // Draw the glyphs.
        // positions these are x&y (0,offsets[i])
        val textPaint = Paint(Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
        textPaint.setColor(textColor)
        //textPaint.setTextSize(myfont.fontSize)
        //textPaint.setTypeface(myfont.typeface)

        for (i in 0 until glyphs.count()) {
            //val textstr = myfont.getGlyphString(glyphs[i])
            canvas.save()
            canvas.translate(0f, offsets[i])
            canvas.scale(1.0f, -1.0f)
            drawer.drawGlyph(canvas, textPaint, glyphs[i], 0.0f, 0.0f)

            //canvas.drawText(textstr, 0.0f, 0.0f, textPaint)
            canvas.restore()
        }

        canvas.restore()
    }

    override var ascent: Float
        get() = super.ascent - this.shiftDown
        set(value) {
            super.ascent = value
        }

    override var descent: Float
        get() = super.descent + this.shiftDown
        set(value) {
            super.descent = value
        }

}

// MTLargeOpLimitsDisplay

class MTLargeOpLimitsDisplay(val nucleus: MTDisplay, var upperLimit: MTMathListDisplay?, var lowerLimit: MTMathListDisplay?, var limitShift: Float, var extraPadding: Float) :
        MTDisplay() {


    init {
        var maxWidth: Float = nucleus.width
        if (upperLimit != null) {
            maxWidth = maxOf(maxWidth, upperLimit!!.width)
        }
        if (lowerLimit != null) {
            maxWidth = maxOf(maxWidth, lowerLimit!!.width)
        }
        this.width = maxWidth
    }

    override var ascent
        get() = if (this.upperLimit != null) {
            nucleus.ascent + extraPadding + upperLimit!!.ascent + upperLimitGap + upperLimit!!.descent
        } else {
            nucleus.ascent
        }
        set(value) {
        }

    override var descent: Float
        get() = if (this.lowerLimit != null) {
            nucleus.descent + extraPadding + lowerLimitGap + lowerLimit!!.descent + lowerLimit!!.ascent
        } else {
            nucleus.descent
        }
        set(value) {
        }


    var lowerLimitGap: Float = 0.0f
        set(value) {
            field = value
            this.updateLowerLimitPosition()
        }


    var upperLimitGap: Float = 0.0f
        set(value) {
            field = value
            this.updateUpperLimitPosition()

        }

    override fun positionChanged() {
        this.updateLowerLimitPosition()
        this.updateUpperLimitPosition()
        this.updateNucleusPosition()
    }

    fun updateLowerLimitPosition() {
        val ll = this.lowerLimit
        if (ll != null) {
            // The position of the lower limit includes the position of the MTLargeOpLimitsDisplay
            // This is to make the positioning of the radical consistent with fractions and radicals
            // Move the starting point to below the nucleus leaving a gap of _lowerLimitGap and subtract
            // the ascent to to get the baseline. Also center and shift it to the left by _limitShift.
            ll.position = CGPoint(position.x - limitShift + (this.width - ll.width) / 2,
                    position.y - nucleus.descent - lowerLimitGap - ll.ascent)
        }
    }

    fun updateUpperLimitPosition() {
        val ul = this.upperLimit
        if (ul != null) {
            // The position of the upper limit includes the position of the MTLargeOpLimitsDisplay
            // This is to make the positioning of the radical consistent with fractions and radicals
            // Move the starting point to above the nucleus leaving a gap of _upperLimitGap and add
            // the descent to to get the baseline. Also center and shift it to the right by _limitShift.
            ul.position = CGPoint(position.x + limitShift + (this.width - ul.width) / 2,
                    position.y + nucleus.ascent + upperLimitGap + ul.descent)
        }
    }

    fun updateNucleusPosition() {
        // Center the nucleus
        nucleus.position = CGPoint(position.x + (this.width - nucleus.width) / 2, position.y)
    }

    override fun colorChanged() {
        this.nucleus.textColor = this.textColor
        val ul = this.upperLimit
        if (ul != null) {
            ul.textColor = this.textColor
        }
        val ll = this.lowerLimit
        if (ll != null) {
            ll.textColor = this.textColor
        }
    }


    override fun draw(canvas: Canvas) {
        // Draw the elements.
        upperLimit?.draw(canvas)
        lowerLimit?.draw(canvas)
        nucleus.draw(canvas)

    }
}

// MTLineDisplay  overline or underline
class MTLineDisplay(val inner: MTMathListDisplay, range: NSRange) :
        MTDisplay(range = range) {
    // How much the line should be moved up.
    var lineShiftUp: Float = 0.0f
    var lineThickness: Float = 0.0f

    override fun colorChanged() {
        this.inner.textColor = this.textColor
    }

    override fun draw(canvas: Canvas) {
        this.inner.draw(canvas)

        if (lineThickness != 0f) {
            val strokePaint = Paint(Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
            strokePaint.setColor(textColor)
            strokePaint.strokeWidth = lineThickness
            canvas.drawLine(position.x, position.y + lineShiftUp, position.x + width, position.y + lineShiftUp,
                    strokePaint)
        }

    }

    override fun positionChanged() {
        this.updateInnerPosition()
    }

    fun updateInnerPosition() {
        this.inner.position = CGPoint(this.position.x, this.position.y)
    }

}

// MTAccentDisplay

class MTAccentDisplay(val accent: MTGlyphDisplay, val accentee: MTMathListDisplay, range: NSRange) :
        MTDisplay(range = range) {
    init {
        accentee.position = CGPoint()
        super.range = range.copy()
    }

    override fun colorChanged() {
        this.accentee.textColor = this.textColor
        this.accent.textColor = this.textColor
    }

    override fun positionChanged() {
        this.updateAccenteePosition()
    }

    fun updateAccenteePosition() {
        this.accentee.position = CGPoint(this.position.x, this.position.y)
    }

    override fun draw(canvas: Canvas) {
        this.accentee.draw(canvas)

        canvas.save()

        canvas.translate(position.x, position.y)
        this.accent.draw(canvas)
        canvas.restore()

    }
}

