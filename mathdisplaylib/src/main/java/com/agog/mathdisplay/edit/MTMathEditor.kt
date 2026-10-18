// The model of a visual formula editor: a math list, the caret (an MTMathListIndex) and the edits
// a math keyboard makes. No UI: a view draws `mathList` with MTTypesetter and moves the caret with
// MTDisplay.closestIndex.
//
// Ported from the SwiftMath MathMate fork (MTMathEditor.swift), whose key handling follows
// MathEditor's MTEditableMathLabel (MIT, Kostub Deshmukh).
package com.agog.mathdisplay.edit

import com.agog.mathdisplay.edit.MTMathListIndex.SubIndexType
import com.agog.mathdisplay.parse.MTAccent
import com.agog.mathdisplay.parse.MTFraction
import com.agog.mathdisplay.parse.MTMathAtom
import com.agog.mathdisplay.parse.MTMathAtomType
import com.agog.mathdisplay.parse.MTMathList
import com.agog.mathdisplay.parse.MTMathListBuilder
import com.agog.mathdisplay.parse.MTOverLine
import com.agog.mathdisplay.parse.MTParseError
import com.agog.mathdisplay.parse.MTParseErrors
import com.agog.mathdisplay.parse.MTRadical
import com.agog.mathdisplay.parse.MTUnderLine

class MTMathEditor private constructor(list: MTMathList) {
    /** The formula. Empty slots are placeholder atoms; the one at the caret is drawn black. */
    var mathList: MTMathList = list
        private set

    /** Where the next atom goes. On an empty slot it points at the slot, which typing replaces. */
    var caret: MTMathListIndex = MTMathListIndex(list.atoms.size)
        private set

    init {
        normalizeCaret()
    }

    companion object {
        /** Starts from LaTeX; null when the formula cannot be edited visually (edit it as LaTeX instead). */
        fun create(latex: String = ""): MTMathEditor? {
            val trimmed = latex.trim()
            if (trimmed.isEmpty()) return MTMathEditor(MTMathList())
            val error = MTParseError()
            val list = MTMathListBuilder.buildFromString(editable(trimmed), error) ?: return null
            if (error.errorcode != MTParseErrors.ErrorNone || containsUneditable(list)) return null
            return MTMathEditor(list)
        }

        private const val WHITE_SQUARE = "□"
        private const val BLACK_SQUARE = "■"

        fun placeholder() = MTMathAtom(MTMathAtomType.KMTMathAtomPlaceholder, WHITE_SQUARE)

        fun isEmptySlot(atom: MTMathAtom) =
            atom.type == MTMathAtomType.KMTMathAtomPlaceholder && atom.superScript == null && atom.subScript == null

        fun isEmpty(list: MTMathList?) = list?.atoms?.all(::isEmptySlot) ?: true

        /**
         * LaTeX the editor can work in: `\left(…\right)` brackets become plain ones (whose content
         * stays editable), and `\dfrac` a `\frac`.
         */
        fun editable(latex: String): String {
            var out = latex
            for ((from, to) in listOf(
                "\\left(" to "(", "\\right)" to ")", "\\left[" to "[", "\\right]" to "]",
                "\\left\\{" to "\\{", "\\right\\}" to "\\}", "\\left|" to "|", "\\right|" to "|",
                "\\left." to "", "\\right." to "", "\\dfrac" to "\\frac", "\\tfrac" to "\\frac",
            )) out = out.replace(from, to)
            return out
        }

        /** Tables (`cases`, matrices) and `\left…\right` groups have no caret positions inside. */
        private fun containsUneditable(list: MTMathList): Boolean = list.atoms.any { atom ->
            atom.type == MTMathAtomType.KMTMathAtomTable || atom.type == MTMathAtomType.KMTMathAtomInner ||
                atom.editableSubLists.any { containsUneditable(it.second) }
        }

        /** Orders two indexes in reading order (a position inside an atom comes after the place before it). */
        fun compare(a: MTMathListIndex, b: MTMathListIndex): Int {
            if (a.atomIndex != b.atomIndex) return if (a.atomIndex < b.atomIndex) -1 else 1
            val x = a.subIndex
            val y = b.subIndex
            return when {
                x == null && y == null -> 0
                x == null -> -1
                y == null -> 1
                a.subIndexType != b.subIndexType -> {
                    val order = listOf(
                        SubIndexType.NUCLEUS, SubIndexType.NUMERATOR, SubIndexType.DENOMINATOR, SubIndexType.DEGREE,
                        SubIndexType.RADICAND, SubIndexType.INNER_LIST, SubIndexType.SUPERSCRIPT, SubIndexType.SUBSCRIPT,
                    )
                    if (order.indexOf(a.subIndexType) < order.indexOf(b.subIndexType)) -1 else 1
                }
                else -> compare(x, y)
            }
        }

        /** A character typed on the keyboard as an atom. */
        fun atomFor(text: String): MTMathAtom? {
            when (text) {
                "*", "×" -> return MTMathAtom.times()
                "÷" -> return MTMathAtom.atomForLatexSymbolName("div")
                "·" -> return MTMathAtom.atomForLatexSymbolName("cdot")
                " " -> return null
            }
            if (text.length == 1) MTMathAtom.atomForCharacter(text[0])?.let { return it }
            // Unicode symbols from the keyboard (≤, π, ∠…): the LaTeX command they stand for.
            MTMathAtom.atomForUnicodeSymbol(text)?.let { return it }
            return MTMathAtom(MTMathAtomType.KMTMathAtomOrdinary, text)
        }
    }

    val isEmpty: Boolean get() = mathList.atoms.all(::isEmptySlot)

    /** The formula as LaTeX, without the empty slots. */
    val latex: String get() = MTMathListBuilder.toLatexString(withoutSlots(mathList)).trim()

    // Typing

    /**
     * One key of the math keyboard:
     * - a character (`x`, `2`, `+`, `≤`, `π`…) or a LaTeX command (`\le`, `\pi`, `\perp`…);
     * - `^` exponent, `_` subscript, `/` fraction (the number or letters before it become the
     *   numerator), `\sqrt` square root, `\nthroot` root with a degree;
     * - `()` and `||`: a pair with the caret inside.
     */
    fun insert(key: String) {
        when (key) {
            "^" -> exponentOrSubscript(SubIndexType.SUPERSCRIPT)
            "_" -> exponentOrSubscript(SubIndexType.SUBSCRIPT)
            "/" -> fraction()
            "\\sqrt", "√" -> insertTemplate("\\sqrt{#?}")
            "\\nthroot", "∛" -> insertTemplate("\\sqrt[#?]{#?}")
            "()" -> insertTemplate("(#?)")
            "||" -> insertTemplate("|#?|")
            else -> if (key.startsWith("\\")) {
                val atom = MTMathAtom.atomForLatexSymbolName(key.substring(1))
                if (atom != null) place(atom) else insertTemplate(key)
            } else {
                var index = 0
                while (index < key.length) {
                    val end = index + Character.charCount(key.codePointAt(index))
                    atomFor(key.substring(index, end))?.let(::place)
                    index = end
                }
            }
        }
        normalizeCaret()
    }

    /**
     * A template from the formula gallery: `#?` is an empty slot and `#@` takes the term just
     * before the caret (or becomes a slot). The caret goes to the first slot. Returns false when
     * the template cannot be read.
     */
    fun insertTemplate(template: String): Boolean {
        val (list, position) = mathList.listContaining(caret) ?: return false
        var latex = editable(template)
        var start = position
        if (latex.contains("#@")) {
            // The term before the caret goes into the template (x → x², 3 → ³⁄…).
            val taken = termBefore(position, list)
            val term = MTMathList()
            for (i in taken) term.addAtom(list.atoms[i])
            val source = if (taken.isEmpty()) "\\square " else "{" + MTMathListBuilder.toLatexString(term) + "}"
            latex = latex.replace("#@", source)
            if (!taken.isEmpty()) {
                list.atoms.subList(taken.first, taken.last + 1).clear()
                start = taken.first
            }
        }
        latex = latex.replace("#?", "\\square ")
        val error = MTParseError()
        val parsed = MTMathListBuilder.buildFromString(latex, error)
        if (parsed == null || error.errorcode != MTParseErrors.ErrorNone) return false
        if (start < list.atoms.size && isEmptySlot(list.atoms[start])) list.atoms.removeAt(start)
        parsed.atoms.forEachIndexed { offset, atom -> list.insertAtom(atom, start + offset) }
        // To the first empty slot of what was inserted, else just after it.
        val first = caret.replacingInnermost(start)
        val end = caret.replacingInnermost(start + parsed.atoms.size)
        val positions = caretPositions
        val from = positions.indexOf(first)
        val slot = if (from >= 0) {
            positions.drop(from).firstOrNull { position ->
                position != end && mathList.atomAt(position)?.let(::isEmptySlot) == true && isInside(position, first, end)
            }
        } else {
            null
        }
        caret = slot ?: end
        normalizeCaret()
        return true
    }

    /**
     * Backspace. Deletes the atom before the caret; a structure with content is entered rather
     * than deleted, and at the start of a part an empty structure is removed (else the caret
     * moves back), so nothing typed is lost by one key.
     */
    fun deleteBackward() {
        val (list, position) = mathList.listContaining(caret) ?: return
        if (position > 0) {
            val atom = list.atoms[position - 1]
            if (!isEmptySlot(atom) && atom.editableSubLists.any { !isEmpty(it.second) }) {
                moveLeft() // into the fraction, root, script… from its end
                return
            }
            list.atoms.removeAt(position - 1)
            caret = caret.replacingInnermost(position - 1)
            fillIfEmpty(list)
            normalizeCaret()
            return
        }
        // At the start of a part.
        val parentIndex = caret.parent ?: return
        val owner = mathList.atomAt(parentIndex) ?: return
        val (parentList, ownerPosition) = mathList.listContaining(parentIndex) ?: return
        val part = caret.innermostType
        if (part == SubIndexType.SUPERSCRIPT || part == SubIndexType.SUBSCRIPT) {
            if (!isEmpty(owner.subList(part))) {
                moveLeft()
                return
            }
            if (part == SubIndexType.SUPERSCRIPT) owner.superScript = null else owner.subScript = null
            if (isEmptySlot(owner) && owner.superScript == null && owner.subScript == null) {
                parentList.atoms.removeAt(ownerPosition)
                caret = parentIndex
                fillIfEmpty(parentList)
            } else {
                caret = parentIndex.next
            }
        } else if (owner.editableSubLists.all { isEmpty(it.second) }) {
            parentList.atoms.removeAt(ownerPosition)
            caret = parentIndex
            fillIfEmpty(parentList)
        } else {
            moveLeft()
        }
        normalizeCaret()
    }

    // Moving the caret

    /** Every place the caret can be, in reading order (inside structures too). */
    val caretPositions: List<MTMathListIndex> get() = positions(mathList) { it }

    fun moveLeft() {
        val positions = caretPositions
        val current = positions.indexOf(caret)
        if (current > 0) caret = positions[current - 1]
        normalizeCaret()
    }

    fun moveRight() {
        val positions = caretPositions
        val current = positions.indexOf(caret)
        if (current >= 0 && current + 1 < positions.size) caret = positions[current + 1]
        normalizeCaret()
    }

    /** Puts the caret at a tapped index (from MTDisplay.closestIndex), or the nearest valid one. */
    fun moveCaret(index: MTMathListIndex) {
        var target = index
        val parent = target.parent
        if (target.innermostType == SubIndexType.NUCLEUS && parent != null) {
            target = parent.next // between a nucleus and its scripts: after the atom
        }
        caret = target
        normalizeCaret()
    }

    fun moveToEnd() {
        caret = MTMathListIndex(mathList.atoms.size)
        normalizeCaret()
    }

    // Internals

    private fun place(atom: MTMathAtom) {
        val (list, position) = mathList.listContaining(caret) ?: return
        // A relation typed in a denominator goes after the fraction (½ = …).
        val parent = caret.parent
        if (atom.type == MTMathAtomType.KMTMathAtomRelation && caret.innermostType == SubIndexType.DENOMINATOR && parent != null) {
            caret = parent.next
            place(atom)
            return
        }
        if (position < list.atoms.size && isEmptySlot(list.atoms[position])) {
            val slot = list.atoms[position]
            if (atom.scriptsAllowed()) {
                atom.superScript = slot.superScript
                atom.subScript = slot.subScript
            }
            list.atoms.removeAt(position)
        }
        list.insertAtom(atom, position)
        caret = caret.replacingInnermost(position + 1)
    }

    private fun exponentOrSubscript(type: SubIndexType) {
        if (caret.contains(type)) {
            // Pressed again inside it: out, after the atom.
            var index = caret
            while (index.contains(type)) index = index.parent ?: break
            caret = index.next
            return
        }
        val (list, position) = mathList.listContaining(caret) ?: return
        val base: MTMathAtom
        val baseIndex: MTMathListIndex
        if (position > 0 && list.atoms[position - 1].scriptsAllowed()) {
            base = list.atoms[position - 1]
            baseIndex = caret.replacingInnermost(position - 1)
        } else {
            // Nothing to put it on: an empty slot carries it.
            if (position < list.atoms.size && isEmptySlot(list.atoms[position])) {
                base = list.atoms[position]
            } else {
                base = placeholder()
                list.insertAtom(base, position)
            }
            baseIndex = caret.replacingInnermost(position)
        }
        val existing = base.subList(type)
        if (existing == null) {
            val script = MTMathList()
            script.addAtom(placeholder())
            if (type == SubIndexType.SUPERSCRIPT) base.superScript = script else base.subScript = script
            caret = baseIndex.into(type, 0)
        } else {
            caret = baseIndex.into(type, existing.atoms.size)
        }
    }

    /** `/`: the number or letters just before the caret become the numerator (MathEditor). */
    private fun fraction() {
        val (list, position) = mathList.listContaining(caret) ?: return
        val taken = termBefore(position, list, numbersAndLettersOnly = true)
        val numerator = MTMathList()
        for (i in taken) numerator.addAtom(list.atoms[i])
        if (!taken.isEmpty()) list.atoms.subList(taken.first, taken.last + 1).clear()
        if (numerator.atoms.isEmpty()) numerator.addAtom(placeholder())
        val denominator = MTMathList()
        denominator.addAtom(placeholder())
        val fraction = MTFraction()
        fraction.numerator = numerator
        fraction.denominator = denominator
        val start = if (taken.isEmpty()) position else taken.first
        if (start < list.atoms.size && isEmptySlot(list.atoms[start])) list.atoms.removeAt(start)
        list.insertAtom(fraction, start)
        val fractionIndex = caret.replacingInnermost(start)
        caret = if (taken.isEmpty()) fractionIndex.into(SubIndexType.NUMERATOR, 0) else fractionIndex.into(SubIndexType.DENOMINATOR, 0)
    }

    /** The caret never sits just after an empty slot: it goes onto the slot. Slots are drawn white, the one at the caret black. */
    private fun normalizeCaret() {
        val positions = caretPositions
        if (!positions.contains(caret)) {
            val previous = caret.previous
            caret = if (previous != null && positions.contains(previous)) {
                previous
            } else {
                positions.lastOrNull { isInside(it, MTMathListIndex(0), caret) } ?: positions.lastOrNull() ?: MTMathListIndex(0)
            }
        }
        paintSlots(mathList)
        val atom = mathList.atomAt(caret)
        if (atom != null && atom.type == MTMathAtomType.KMTMathAtomPlaceholder && caret.innermostType != SubIndexType.NUCLEUS) {
            atom.nucleus = BLACK_SQUARE
        }
    }

    private fun fillIfEmpty(list: MTMathList) {
        if (list !== mathList && list.atoms.isEmpty()) list.addAtom(placeholder())
    }

    private fun paintSlots(list: MTMathList) {
        for (atom in list.atoms) {
            if (atom.type == MTMathAtomType.KMTMathAtomPlaceholder) atom.nucleus = WHITE_SQUARE
            for ((_, sub) in atom.editableSubLists) paintSlots(sub)
        }
    }

    /**
     * Caret places in a list: before each atom (not right after an empty slot: the slot itself is
     * the place), inside each atom's parts, and at the end.
     */
    private fun positions(list: MTMathList, wrap: (MTMathListIndex) -> MTMathListIndex): List<MTMathListIndex> {
        val out = mutableListOf<MTMathListIndex>()
        list.atoms.forEachIndexed { position, atom ->
            if (position == 0 || !isEmptySlot(list.atoms[position - 1])) out.add(wrap(MTMathListIndex(position)))
            for ((type, sub) in atom.editableSubLists) {
                out += positions(sub) { inner -> wrap(MTMathListIndex.at(position, inner, type)) }
            }
        }
        if (list.atoms.lastOrNull()?.let(::isEmptySlot) != true) out.add(wrap(MTMathListIndex(list.atoms.size)))
        return out
    }

    /** The atoms before `position` that form one term: a run of numbers and letters, or a single structure. */
    private fun termBefore(position: Int, list: MTMathList, numbersAndLettersOnly: Boolean = false): IntRange {
        var start = position
        while (start > 0 && list.atoms[start - 1].type in listOf(MTMathAtomType.KMTMathAtomNumber, MTMathAtomType.KMTMathAtomVariable)) start -= 1
        if (start == position && !numbersAndLettersOnly && position > 0 && list.atoms[position - 1].type in listOf(
                MTMathAtomType.KMTMathAtomFraction, MTMathAtomType.KMTMathAtomRadical, MTMathAtomType.KMTMathAtomAccent,
                MTMathAtomType.KMTMathAtomOverline, MTMathAtomType.KMTMathAtomInner, MTMathAtomType.KMTMathAtomClose,
            )
        ) {
            start = position - 1
        }
        return start until position
    }

    /** True when `index` is at or after `from` and before `to` (same tree). */
    private fun isInside(index: MTMathListIndex, from: MTMathListIndex, to: MTMathListIndex) =
        compare(index, from) >= 0 && compare(index, to) < 0

    /** A copy without empty slots, for the LaTeX output. */
    private fun withoutSlots(list: MTMathList): MTMathList {
        val out = MTMathList()
        for (atom in list.atoms) {
            if (isEmptySlot(atom)) continue
            val copy = atom.copyDeep()
            if (copy.type == MTMathAtomType.KMTMathAtomPlaceholder) {
                copy.type = MTMathAtomType.KMTMathAtomOrdinary
                copy.nucleus = ""
            }
            copy.superScript = copy.superScript?.let(::withoutSlots)
            copy.subScript = copy.subScript?.let(::withoutSlots)
            when (copy) {
                is MTFraction -> {
                    copy.numerator = copy.numerator?.let(::withoutSlots)
                    copy.denominator = copy.denominator?.let(::withoutSlots)
                }
                is MTRadical -> {
                    copy.radicand = copy.radicand?.let(::withoutSlots)
                    copy.degree = copy.degree?.let { if (isEmpty(it)) null else withoutSlots(it) }
                }
                is MTAccent -> copy.innerList = copy.innerList?.let(::withoutSlots)
                is MTOverLine -> copy.innerList = copy.innerList?.let(::withoutSlots)
                is MTUnderLine -> copy.innerList = copy.innerList?.let(::withoutSlots)
            }
            out.addAtom(copy)
        }
        return out
    }
}
