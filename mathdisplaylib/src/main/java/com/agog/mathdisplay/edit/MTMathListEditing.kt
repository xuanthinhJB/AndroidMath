// Inserting, removing and finding atoms at an MTMathListIndex: a path into nested lists
// (fractions, radicals, scripts, and the content under accents and lines).
//
// Ported from MathEditor (https://github.com/kostub/MathEditor), MTMathList+Editing.m, through
// the SwiftMath MathMate fork. Copyright (C) 2013 MathChat. MIT license.
package com.agog.mathdisplay.edit

import com.agog.mathdisplay.edit.MTMathListIndex.SubIndexType
import com.agog.mathdisplay.parse.MTAccent
import com.agog.mathdisplay.parse.MTFraction
import com.agog.mathdisplay.parse.MTMathAtom
import com.agog.mathdisplay.parse.MTMathList
import com.agog.mathdisplay.parse.MTOverLine
import com.agog.mathdisplay.parse.MTRadical
import com.agog.mathdisplay.parse.MTUnderLine

/** The list a sub-index of this atom leads into, or null when the atom has no such part. */
fun MTMathAtom.subList(type: SubIndexType): MTMathList? = when (type) {
    SubIndexType.SUPERSCRIPT -> superScript
    SubIndexType.SUBSCRIPT -> subScript
    SubIndexType.NUMERATOR -> (this as? MTFraction)?.numerator
    SubIndexType.DENOMINATOR -> (this as? MTFraction)?.denominator
    SubIndexType.RADICAND -> (this as? MTRadical)?.radicand
    SubIndexType.DEGREE -> (this as? MTRadical)?.degree
    SubIndexType.INNER_LIST -> when (this) {
        is MTAccent -> innerList
        is MTOverLine -> innerList
        is MTUnderLine -> innerList
        else -> null
    }
    SubIndexType.NONE, SubIndexType.NUCLEUS -> null
}

/** The parts of this atom that can be edited, in reading order (scripts last). */
val MTMathAtom.editableSubLists: List<Pair<SubIndexType, MTMathList>>
    get() = listOf(
        SubIndexType.NUMERATOR, SubIndexType.DENOMINATOR, SubIndexType.DEGREE, SubIndexType.RADICAND,
        SubIndexType.INNER_LIST, SubIndexType.SUPERSCRIPT, SubIndexType.SUBSCRIPT,
    ).mapNotNull { type -> subList(type)?.let { type to it } }

/** Inserts `atom` before the atom at `index` (which may be inside a sub-list). */
fun MTMathList.insert(atom: MTMathAtom, index: MTMathListIndex) {
    if (index.atomIndex > atoms.size) return
    when (index.subIndexType) {
        SubIndexType.NONE -> insertAtom(atom, index.atomIndex)
        SubIndexType.NUCLEUS -> {
            // Inside the nucleus of an atom with scripts: the new atom takes the scripts over.
            if (index.atomIndex >= atoms.size) return
            val current = atoms[index.atomIndex]
            if (atom.subScript != null || atom.superScript != null || !atom.scriptsAllowed()) return
            atom.subScript = current.subScript
            atom.superScript = current.superScript
            current.subScript = null
            current.superScript = null
            insertAtom(atom, index.atomIndex + (index.subIndex?.atomIndex ?: 0))
        }
        else -> {
            if (index.atomIndex >= atoms.size) return
            val sub = index.subIndex ?: return
            atoms[index.atomIndex].subList(index.subIndexType)?.insert(atom, sub)
        }
    }
}

/** Removes the atom at `index`. */
fun MTMathList.removeAtom(index: MTMathListIndex) {
    if (index.atomIndex >= atoms.size) return
    when (index.subIndexType) {
        SubIndexType.NONE -> atoms.removeAt(index.atomIndex)
        SubIndexType.NUCLEUS -> {
            val current = atoms[index.atomIndex]
            val previous = atoms.getOrNull(index.atomIndex - 1)
            if (previous != null && previous.subScript == null && previous.superScript == null && previous.scriptsAllowed()) {
                // The previous atom takes the scripts over.
                previous.superScript = current.superScript
                previous.subScript = current.subScript
                atoms.removeAt(index.atomIndex)
            } else {
                current.nucleus = ""
            }
        }
        else -> {
            val sub = index.subIndex ?: return
            atoms[index.atomIndex].subList(index.subIndexType)?.removeAtom(sub)
        }
    }
}

/** The atom at `index`, or null. */
fun MTMathList.atomAt(index: MTMathListIndex?): MTMathAtom? {
    if (index == null || index.atomIndex >= atoms.size || index.atomIndex < 0) return null
    val atom = atoms[index.atomIndex]
    return when (index.subIndexType) {
        SubIndexType.NONE, SubIndexType.NUCLEUS -> atom
        else -> atom.subList(index.subIndexType)?.atomAt(index.subIndex)
    }
}

/** The list that `index` points into (the innermost one) and the position in it. */
fun MTMathList.listContaining(index: MTMathListIndex): Pair<MTMathList, Int>? = when (index.subIndexType) {
    SubIndexType.NONE -> this to index.atomIndex
    SubIndexType.NUCLEUS -> this to index.atomIndex + (index.subIndex?.atomIndex ?: 0)
    else -> {
        val sub = index.subIndex
        val list = atoms.getOrNull(index.atomIndex)?.subList(index.subIndexType)
        if (sub == null || list == null) null else list.listContaining(sub)
    }
}
