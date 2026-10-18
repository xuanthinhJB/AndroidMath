// Hit-testing and caret positions for a math editor: which index of the math list a point falls
// on, and where the caret for an index is drawn.
//
// Ported from MathEditor (https://github.com/kostub/MathEditor), MTDisplay+Editing.m, through the
// SwiftMath MathMate fork. Copyright (C) 2013 MathChat. MIT license.
package com.agog.mathdisplay.edit

import com.agog.mathdisplay.edit.MTMathListIndex.SubIndexType
import com.agog.mathdisplay.parse.NSRange
import com.agog.mathdisplay.render.CGPoint
import com.agog.mathdisplay.render.CGRect
import com.agog.mathdisplay.render.MTAccentDisplay
import com.agog.mathdisplay.render.MTCTLineDisplay
import com.agog.mathdisplay.render.MTDisplay
import com.agog.mathdisplay.render.MTFractionDisplay
import com.agog.mathdisplay.render.MTLineDisplay
import com.agog.mathdisplay.render.MTLinePosition
import com.agog.mathdisplay.render.MTMathListDisplay
import com.agog.mathdisplay.render.MTRadicalDisplay

/** How far outside a display's bounds a point still counts as inside it. */
private const val PIXEL_DELTA = 2f

private val NSRange.end get() = location + length
private fun NSRange.holds(index: Int) = index >= location && index < location + length

/** Manhattan distance from a point to the nearest edge of a rectangle (0 inside it). */
private fun distance(point: CGPoint, rect: CGRect): Float {
    var d = 0f
    if (point.x < rect.x) d += rect.x - point.x else if (point.x > rect.x + rect.width) d += point.x - (rect.x + rect.width)
    if (point.y < rect.y) d += rect.y - point.y else if (point.y > rect.y + rect.height) d += point.y - (rect.y + rect.height)
    return d
}

/**
 * The index in the math list before which a character typed at `point` goes, or null. `point`
 * is in the display's coordinates: x to the right and y up from the baseline, with the display
 * at its `position`.
 */
fun MTDisplay.closestIndex(point: CGPoint): MTMathListIndex? = when (this) {
    is MTMathListDisplay -> listClosestIndex(point)
    is MTCTLineDisplay -> lineClosestIndex(point)
    is MTFractionDisplay -> fractionClosestIndex(point)
    is MTRadicalDisplay -> radicalClosestIndex(point)
    is MTAccentDisplay -> wrapperClosestIndex(point, accentee)
    is MTLineDisplay -> wrapperClosestIndex(point, inner)
    else -> null
}

/**
 * Where the caret for `index` is drawn (on the baseline), in the same coordinates as
 * `closestIndex`; null when the index is not in this display.
 */
fun MTDisplay.caretPosition(index: MTMathListIndex?): CGPoint? {
    if (index == null) return null
    return when (this) {
        is MTMathListDisplay -> listCaretPosition(index)
        is MTCTLineDisplay -> lineCaretPosition(index)
        // The caret goes before the fraction, radical, accent or line.
        is MTFractionDisplay, is MTRadicalDisplay, is MTAccentDisplay, is MTLineDisplay ->
            if (index.subIndexType == SubIndexType.NONE) position.copy() else null
        else -> null
    }
}

/** An accent or an over/underline: before it, after it, or in its content. */
private fun MTDisplay.wrapperClosestIndex(point: CGPoint, inner: MTMathListDisplay?): MTMathListIndex? {
    if (point.x < position.x - PIXEL_DELTA) return MTMathListIndex(range.location)
    if (point.x > position.x + width + PIXEL_DELTA) return MTMathListIndex(range.end)
    if (inner == null) return null
    return MTMathListIndex.at(range.location, inner.closestIndex(point), SubIndexType.INNER_LIST)
}

// A line of text

/**
 * The string offset of each atom of the line: the line draws the atoms' (styled) nuclei, one
 * code point per character, so atom k starts after the code points of the atoms before it.
 */
private fun MTCTLineDisplay.stringIndexForAtom(atomIndex: Int): Int {
    val codePoints = atoms.take(atomIndex).sumOf { it.nucleus.codePointCount(0, it.nucleus.length) }
    var offset = 0
    repeat(codePoints) { if (offset < str.length) offset += Character.charCount(str.codePointAt(offset)) }
    return minOf(offset, str.length)
}

private fun MTCTLineDisplay.atomForStringIndex(stringIndex: Int): Int {
    for (atomIndex in atoms.indices) {
        if (stringIndexForAtom(atomIndex) >= stringIndex) return atomIndex
    }
    return atoms.size
}

private fun MTCTLineDisplay.lineClosestIndex(point: CGPoint): MTMathListIndex {
    val stringIndex = stringIndexForOffset(point.x - position.x)
    return MTMathListIndex(range.location + atomForStringIndex(stringIndex))
}

private fun MTCTLineDisplay.lineCaretPosition(index: MTMathListIndex): CGPoint? {
    if (index.subIndexType != SubIndexType.NONE) return null
    if (index.atomIndex < range.location || index.atomIndex > range.end) return null
    val offset = offsetForStringIndex(stringIndexForAtom(index.atomIndex - range.location))
    return CGPoint(position.x + offset, position.y)
}

// Fractions and radicals

private fun MTFractionDisplay.fractionClosestIndex(point: CGPoint): MTMathListIndex? {
    if (point.x < position.x - PIXEL_DELTA) return MTMathListIndex(range.location) // before the fraction
    if (point.x > position.x + width + PIXEL_DELTA) return MTMathListIndex(range.end) // after it
    return if (distance(point, numerator.displayBounds()) < distance(point, denominator.displayBounds())) {
        MTMathListIndex.at(range.location, numerator.closestIndex(point), SubIndexType.NUMERATOR)
    } else {
        MTMathListIndex.at(range.location, denominator.closestIndex(point), SubIndexType.DENOMINATOR)
    }
}

private fun MTRadicalDisplay.radicalClosestIndex(point: CGPoint): MTMathListIndex? {
    if (point.x < position.x - PIXEL_DELTA) return MTMathListIndex(range.location)
    if (point.x > position.x + width + PIXEL_DELTA) return MTMathListIndex(range.end)
    val degree = degree
    if (degree != null && distance(point, degree.displayBounds()) < distance(point, radicand.displayBounds())) {
        return MTMathListIndex.at(range.location, degree.closestIndex(point), SubIndexType.DEGREE)
    }
    return MTMathListIndex.at(range.location, radicand.closestIndex(point), SubIndexType.RADICAND)
}

// A list of displays

private fun MTMathListDisplay.listClosestIndex(outer: CGPoint): MTMathListIndex? {
    // Sub-displays are positioned relative to this display.
    val point = CGPoint(outer.x - position.x, outer.y - position.y)
    val displays = subDisplays.orEmpty()
    var closest: MTDisplay? = null
    var minDistance = Float.MAX_VALUE
    val underPoint = mutableListOf<MTDisplay>() // displays whose x-range holds the point
    for (display in displays) {
        val bounds = display.displayBounds()
        if (bounds.x - PIXEL_DELTA <= point.x && point.x <= bounds.x + bounds.width + PIXEL_DELTA) underPoint.add(display)
        val d = distance(point, bounds)
        // A nucleus with a script may overlap the script: on a tie the (smaller, inner) script wins.
        val isScript = (display as? MTMathListDisplay)?.let { it.type != MTLinePosition.KMTLinePositionRegular } ?: false
        if (d < minDistance || (d == minDistance && isScript)) {
            closest = display
            minDistance = d
        }
    }

    val hit: MTDisplay?
    if (underPoint.isEmpty()) {
        if (point.x <= -PIXEL_DELTA) return MTMathListIndex(range.location)
        if (point.x >= width + PIXEL_DELTA) return MTMathListIndex(range.end)
        hit = closest // inside the list but between displays
    } else if (underPoint.size == 1) {
        hit = underPoint[0]
        // Near the end and below the display: the caret goes at the end of the list.
        if (point.x >= width - PIXEL_DELTA && point.y <= hit.displayBounds().y - PIXEL_DELTA) return MTMathListIndex(range.end)
    } else {
        hit = closest // overlapping displays (a nucleus and its script): the nearest wins
    }
    if (hit == null) return null
    val index = hit.closestIndex(point) ?: return null

    if (hit is MTMathListDisplay && hit.type != MTLinePosition.KMTLinePositionRegular) {
        // A subscript or superscript of the atom at `hit.index`.
        val type = if (hit.type == MTLinePosition.KMTLinePositionSubscript) SubIndexType.SUBSCRIPT else SubIndexType.SUPERSCRIPT
        return MTMathListIndex.at(hit.index, index, type)
    }
    if (hit.hasScript && index.atomIndex == hit.range.end) {
        // At the end of an atom with a script: before the script, not after it.
        return MTMathListIndex.at(index.atomIndex - 1, MTMathListIndex(1), SubIndexType.NUCLEUS)
    }
    return index
}

/** The display that holds `index` (not recursing into it). */
private fun MTMathListDisplay.subDisplayFor(index: MTMathListIndex): MTDisplay? {
    val displays = subDisplays.orEmpty()
    if (index.subIndexType == SubIndexType.SUPERSCRIPT || index.subIndexType == SubIndexType.SUBSCRIPT) {
        return displays.firstOrNull { display ->
            display is MTMathListDisplay && display.index == index.atomIndex &&
                ((display.type == MTLinePosition.KMTLinePositionSubscript && index.subIndexType == SubIndexType.SUBSCRIPT) ||
                    (display.type == MTLinePosition.KMTLinePositionSuperscript && index.subIndexType == SubIndexType.SUPERSCRIPT))
        }
    }
    val display = displays.firstOrNull { it !is MTMathListDisplay && it.range.holds(index.atomIndex) } ?: return null
    return when (index.subIndexType) {
        SubIndexType.NONE, SubIndexType.NUCLEUS -> display
        SubIndexType.NUMERATOR -> (display as? MTFractionDisplay)?.numerator
        SubIndexType.DENOMINATOR -> (display as? MTFractionDisplay)?.denominator
        SubIndexType.RADICAND -> (display as? MTRadicalDisplay)?.radicand
        SubIndexType.DEGREE -> (display as? MTRadicalDisplay)?.degree
        SubIndexType.INNER_LIST -> (display as? MTAccentDisplay)?.accentee ?: (display as? MTLineDisplay)?.inner
        SubIndexType.SUPERSCRIPT, SubIndexType.SUBSCRIPT -> null
    }
}

private fun MTMathListDisplay.listCaretPosition(index: MTMathListIndex): CGPoint? {
    var caret: CGPoint? = null
    if (index.atomIndex == range.end) {
        caret = CGPoint(width, 0f)
    } else if (range.holds(index.atomIndex)) {
        val display = subDisplayFor(index)
        if (display != null) {
            caret = when (index.subIndexType) {
                SubIndexType.NUCLEUS -> display.caretPosition(MTMathListIndex(index.atomIndex + (index.subIndex?.atomIndex ?: 0)))
                SubIndexType.NONE -> display.caretPosition(index)
                else -> display.caretPosition(index.subIndex)
            }
        }
    }
    return caret?.let { CGPoint(it.x + position.x, it.y + position.y) }
}
