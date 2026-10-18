// Translated from iosMath and MathEditor (Kostub Deshmukh) through SwiftMath (Mike Griebling)
// and its MathMate fork. MIT license.
package com.agog.mathdisplay.edit

/**
 * An index that points to a particular character in an MTMathList: a path from the start of the
 * list to the atom, through sub-lists (superscript, numerator, the content under an accent…).
 * e.g. in 25^{2/4} the index of the 4 is (1, superscript) → (0, denominator) → (0, none).
 */
class MTMathListIndex private constructor(
    /** The index of the atom in its list. */
    val atomIndex: Int,
    /** Which branch the path takes into the atom, NONE when the path ends here. */
    val subIndexType: SubIndexType,
    /** The rest of the path. */
    val subIndex: MTMathListIndex?,
) {
    enum class SubIndexType {
        /** The index denotes the whole atom, subIndex is null. */
        NONE,
        /** The position in the subindex is an index into the nucleus (fused atoms). */
        NUCLEUS,
        SUPERSCRIPT,
        SUBSCRIPT,
        NUMERATOR,
        DENOMINATOR,
        RADICAND,
        DEGREE,
        /** The content under an accent, an overline or an underline (\widehat{ABC}, \overline{AB}…). */
        INNER_LIST,
    }

    constructor(level0Index: Int) : this(level0Index, SubIndexType.NONE, null)

    companion object {
        fun at(location: Int, subIndex: MTMathListIndex?, type: SubIndexType) = MTMathListIndex(location, type, subIndex)
    }

    val finalIndex: Int get() = if (subIndexType == SubIndexType.NONE) atomIndex else subIndex?.finalIndex ?: 0

    /** The index one atom back in the same list, null at the start of a list. */
    val previous: MTMathListIndex?
        get() = if (subIndexType == SubIndexType.NONE) {
            if (atomIndex > 0) MTMathListIndex(atomIndex - 1) else null
        } else {
            subIndex?.previous?.let { at(atomIndex, it, subIndexType) }
        }

    /** The index one atom further in the same list. */
    val next: MTMathListIndex
        get() = when (subIndexType) {
            SubIndexType.NONE -> MTMathListIndex(atomIndex + 1)
            SubIndexType.NUCLEUS -> at(atomIndex + 1, subIndex, subIndexType)
            else -> at(atomIndex, subIndex?.next, subIndexType)
        }

    /** True at the first position of a list. */
    val isAtStartOfList: Boolean get() = finalIndex == 0

    /** The innermost part this index is in (NONE at the top level). */
    val innermostType: SubIndexType
        get() = if (subIndex?.subIndex != null) subIndex.innermostType else subIndexType

    fun contains(type: SubIndexType): Boolean = subIndexType == type || subIndex?.contains(type) == true

    /** The same position one level deeper, in part `type` of the atom at this index. */
    fun into(type: SubIndexType, position: Int): MTMathListIndex = levelUp(MTMathListIndex(position), type)

    private fun levelUp(sub: MTMathListIndex, type: SubIndexType): MTMathListIndex =
        if (subIndexType == SubIndexType.NONE) at(atomIndex, sub, type) else at(atomIndex, subIndex!!.levelUp(sub, type), subIndexType)

    /** The index of the atom whose part this index is in (one level out), null at the top level. */
    val parent: MTMathListIndex?
        get() {
            if (subIndexType == SubIndexType.NONE) return null
            val down = subIndex?.parent
            return if (down != null) at(atomIndex, down, subIndexType) else MTMathListIndex(atomIndex)
        }

    /** The same path with another position in the innermost list. */
    fun replacingInnermost(position: Int): MTMathListIndex {
        val sub = subIndex
        if (subIndexType == SubIndexType.NONE || subIndexType == SubIndexType.NUCLEUS || sub == null) return MTMathListIndex(position)
        return at(atomIndex, sub.replacingInnermost(position), subIndexType)
    }

    override fun equals(other: Any?): Boolean =
        other is MTMathListIndex && other.atomIndex == atomIndex && other.subIndexType == subIndexType && other.subIndex == subIndex

    override fun hashCode(): Int = (atomIndex * 31 + subIndexType.hashCode()) * 31 + (subIndex?.hashCode() ?: 0)

    override fun toString(): String = if (subIndex != null) "[$atomIndex, ${subIndexType.ordinal}:$subIndex]" else "[$atomIndex]"
}
