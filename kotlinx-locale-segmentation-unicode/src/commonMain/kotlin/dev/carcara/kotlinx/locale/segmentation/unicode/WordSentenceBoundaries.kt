/*
 * Copyright 2026 Carcara.dev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.carcara.kotlinx.locale.segmentation.unicode

/** Boundaries are privately owned by the immutable result; they never escape through the public API. */
internal class SegmentationBoundaries(val boundaries: IntArray, val wordLike: BooleanArray? = null)

/** Immutable, range-compressed UCD properties; absent ranges have property Other. */
private class BoundaryPropertyTable(encoded: String) {
    private val starts: IntArray
    private val ends: IntArray
    private val values: IntArray

    init {
        val entries = encoded.split('\u001E')
        starts = IntArray(entries.size)
        ends = IntArray(entries.size)
        values = IntArray(entries.size)
        for ((i, entry) in entries.withIndex()) {
            val fields = entry.split(',')
            starts[i] = fields[0].toInt(36)
            ends[i] = starts[i] + fields[1].toInt(36)
            values[i] = fields[2].toInt(36)
        }
    }

    fun valueOf(cp: Int): Int {
        var low = 0
        var high = starts.lastIndex
        while (low <= high) {
            val middle = low + (high - low) / 2
            when {
                cp < starts[middle] -> high = middle - 1
                cp > ends[middle] -> low = middle + 1
                else -> return values[middle]
            }
        }
        return 0
    }
}

/** Decode code points without replacing lone surrogates, retaining every original UTF-16 offset. */
private class BoundaryInput(text: String, table: BoundaryPropertyTable, divisor: Int) {
    val classes: IntArray
    val flags: IntArray
    val offsets: IntArray
    val size: Int get() = classes.size

    init {
        val propertyBuffer = IntArray(text.length)
        val flagBuffer = IntArray(text.length)
        val offsetBuffer = IntArray(text.length + 1)
        var utf16 = 0
        var count = 0
        while (utf16 < text.length) {
            offsetBuffer[count] = utf16
            val first = text[utf16++]
            val cp = if (first.isHighSurrogate() && utf16 < text.length && text[utf16].isLowSurrogate()) {
                0x10000 + ((first.code - 0xD800) shl 10) + (text[utf16++].code - 0xDC00)
            } else {
                first.code
            }
            val value = table.valueOf(cp)
            flagBuffer[count] = value
            propertyBuffer[count++] = value / divisor
        }
        offsetBuffer[count] = text.length
        classes = propertyBuffer.copyOf(count)
        flags = flagBuffer.copyOf(count)
        offsets = offsetBuffer.copyOf(count + 1)
    }

    fun collect(wordSegments: Boolean, breaksAt: (Int) -> Boolean): SegmentationBoundaries {
        val boundaries = ArrayList<Int>()
        val wordLike = if (wordSegments) ArrayList<Boolean>() else null
        boundaries.add(0)
        var currentWordLike = false
        for (i in 0 until size) {
            if (i > 0 && breaksAt(i)) {
                boundaries.add(offsets[i])
                wordLike?.add(currentWordLike)
                currentWordLike = false
            }
            currentWordLike = currentWordLike || flags[i] and 1 != 0
        }
        if (size > 0) {
            boundaries.add(offsets[size])
            wordLike?.add(currentWordLike)
        }
        return SegmentationBoundaries(boundaries.toIntArray(), wordLike?.toBooleanArray())
    }
}

/** Complete default UAX #29 word rules for the generated Unicode version. */
internal class WordBoundaryRules(encoded: String) {
    private val table = BoundaryPropertyTable(encoded)

    fun segment(text: String): SegmentationBoundaries {
        val input = BoundaryInput(text, table, 4)
        val context = WordContext(input)
        return input.collect(wordSegments = true, context::breaksAt)
    }
}

private class WordContext(private val input: BoundaryInput) {
    private val p = input.classes
    private val previous = IntArray(input.size)
    private val next = IntArray(input.size + 1) { input.size }
    private val regionalRun = IntArray(input.size)

    init {
        var last = -1
        var run = 0
        for (i in p.indices) {
            if (!ignored(p[i])) {
                last = i
                run = if (p[i] == Wb.REGIONAL_INDICATOR) run + 1 else 0
            }
            previous[i] = last
            regionalRun[i] = run
        }
        for (i in p.indices.reversed()) next[i] = if (ignored(p[i])) next[i + 1] else i
    }

    fun breaksAt(i: Int): Boolean {
        val rawLeft = p[i - 1]
        val right = p[i]
        // WB1/WB2: start/end boundaries are emitted by BoundaryInput.collect.
        // WB3: CR × LF.
        if (rawLeft == Wb.CR && right == Wb.LF) return false
        // WB3a/WB3b: break before and after Newline, CR and LF.
        if (newline(rawLeft) || newline(right)) return true
        // WB3c: ZWJ × Extended_Pictographic. This precedes the ignore rule.
        if (rawLeft == Wb.ZWJ && input.flags[i] and 2 != 0) return false
        // WB3d: WSegSpace × WSegSpace (also before the ignore rule).
        if (rawLeft == Wb.WSEGSPACE && right == Wb.WSEGSPACE) return false
        // WB4: ignore Extend, Format and ZWJ except after sot/newlines.
        if (ignored(right)) return false
        val leftIndex = previous[i - 1]
        if (leftIndex < 0) return true
        val left = p[leftIndex]
        val beforeIndex = if (leftIndex > 0) previous[leftIndex - 1] else -1
        val before = if (beforeIndex >= 0) p[beforeIndex] else Wb.OTHER
        val after = if (next[i + 1] < input.size) p[next[i + 1]] else Wb.OTHER

        // WB5: AHLetter × AHLetter.
        if (letter(left) && letter(right)) return false
        // WB6/WB7: AHLetter × MidLetter/MidNumLetQ AHLetter, and its converse.
        if (letter(left) && midLetter(right) && letter(after)) return false
        if (letter(before) && midLetter(left) && letter(right)) return false
        // WB7a: Hebrew_Letter × Single_Quote.
        if (left == Wb.HEBREW_LETTER && right == Wb.SINGLE_QUOTE) return false
        // WB7b/WB7c: Hebrew_Letter × Double_Quote Hebrew_Letter, and its converse.
        if (left == Wb.HEBREW_LETTER && right == Wb.DOUBLE_QUOTE && after == Wb.HEBREW_LETTER) return false
        if (before == Wb.HEBREW_LETTER && left == Wb.DOUBLE_QUOTE && right == Wb.HEBREW_LETTER) return false
        // WB8/WB9/WB10: Numeric × Numeric, AHLetter × Numeric, Numeric × AHLetter.
        if (left == Wb.NUMERIC && right == Wb.NUMERIC) return false
        if (letter(left) && right == Wb.NUMERIC) return false
        if (left == Wb.NUMERIC && letter(right)) return false
        // WB11/WB12: Numeric MidNum/MidNumLetQ × Numeric, and its converse.
        if (before == Wb.NUMERIC && midNumber(left) && right == Wb.NUMERIC) return false
        if (left == Wb.NUMERIC && midNumber(right) && after == Wb.NUMERIC) return false
        // WB13: Katakana × Katakana.
        if (left == Wb.KATAKANA && right == Wb.KATAKANA) return false
        // WB13a/WB13b: ExtendNumLet joins the letter/numeric/katakana classes.
        if ((letter(left) || left == Wb.NUMERIC || left == Wb.KATAKANA || left == Wb.EXTENDNUMLET) && right == Wb.EXTENDNUMLET) {
            return false
        }
        if (left == Wb.EXTENDNUMLET && (letter(right) || right == Wb.NUMERIC || right == Wb.KATAKANA)) return false
        // WB15/WB16: pair regional indicators, counting through ignored characters.
        if (left == Wb.REGIONAL_INDICATOR && right == Wb.REGIONAL_INDICATOR && regionalRun[leftIndex] % 2 == 1) return false
        // WB999: otherwise break everywhere, including around ideographs.
        return true
    }

    private fun ignored(c: Int): Boolean = c == Wb.EXTEND || c == Wb.FORMAT || c == Wb.ZWJ
    private fun newline(c: Int): Boolean = c == Wb.NEWLINE || c == Wb.CR || c == Wb.LF
    private fun letter(c: Int): Boolean = c == Wb.ALETTER || c == Wb.HEBREW_LETTER
    private fun midLetter(c: Int): Boolean = c == Wb.MIDLETTER || c == Wb.MIDNUMLET || c == Wb.SINGLE_QUOTE
    private fun midNumber(c: Int): Boolean = c == Wb.MIDNUM || c == Wb.MIDNUMLET || c == Wb.SINGLE_QUOTE
}

/** Complete default UAX #29 sentence rules for the generated Unicode version. */
internal class SentenceBoundaryRules(encoded: String) {
    private val table = BoundaryPropertyTable(encoded)

    fun segment(text: String): SegmentationBoundaries {
        val input = BoundaryInput(text, table, 1)
        val context = SentenceContext(input.classes)
        return input.collect(wordSegments = false, context::breaksAt)
    }
}

private class SentenceContext(private val p: IntArray) {
    private val previous = IntArray(p.size)
    private val terminatorClose = IntArray(p.size)
    private val terminatorSpace = IntArray(p.size)
    private val nextBlocking = IntArray(p.size + 1) { p.size }

    init {
        var last = -1
        var close = -1
        var space = -1
        for (i in p.indices) {
            val c = p[i]
            if (!ignored(c)) {
                last = i
                when (c) {
                    Sb.ATERM, Sb.STERM -> {
                        close = i
                        space = i
                    }
                    Sb.CLOSE -> space = close
                    Sb.SP -> close = -1
                    else -> {
                        close = -1
                        space = -1
                    }
                }
            }
            previous[i] = last
            terminatorClose[i] = close
            terminatorSpace[i] = space
        }
        // SB8 lookahead is cached: a long punctuation run must not cause a
        // quadratic rescan for each boundary. The other contexts are suffix states.
        for (i in p.indices.reversed()) {
            val c = p[i]
            nextBlocking[i] = if (c == Sb.OLETTER || c == Sb.UPPER || c == Sb.LOWER || paragraph(c) || term(c)) i else nextBlocking[i + 1]
        }
    }

    fun breaksAt(i: Int): Boolean {
        val rawLeft = p[i - 1]
        val right = p[i]
        // SB1/SB2: start/end boundaries are emitted by BoundaryInput.collect.
        // SB3: CR × LF.
        if (rawLeft == Sb.CR && right == Sb.LF) return false
        // SB4: break after paragraph separators.
        if (paragraph(rawLeft)) return true
        // SB5: ignore Format and Extend except after sot/paragraph separators.
        if (ignored(right)) return false
        val leftIndex = previous[i - 1]
        if (leftIndex < 0) return false
        val left = p[leftIndex]
        val beforeIndex = if (leftIndex > 0) previous[leftIndex - 1] else -1
        val before = if (beforeIndex >= 0) p[beforeIndex] else Sb.OTHER
        // SB6: ATerm × Numeric.
        if (left == Sb.ATERM && right == Sb.NUMERIC) return false
        // SB7: (Upper | Lower) ATerm × Upper.
        if ((before == Sb.UPPER || before == Sb.LOWER) && left == Sb.ATERM && right == Sb.UPPER) return false
        val close = terminatorClose[leftIndex]
        val space = terminatorSpace[leftIndex]
        // SB8: ATerm Close* Sp* × not(OLetter | Upper | Lower | ParaSep | SATerm)* Lower.
        if (space >= 0 && p[space] == Sb.ATERM && nextBlocking[i] < p.size && p[nextBlocking[i]] == Sb.LOWER) return false
        // SB8a: SATerm Close* Sp* × (SContinue | SATerm).
        if (space >= 0 && (right == Sb.SCONTINUE || term(right))) return false
        // SB9: SATerm Close* × (Close | Sp | ParaSep).
        if (close >= 0 && (right == Sb.CLOSE || right == Sb.SP || paragraph(right))) return false
        // SB10: SATerm Close* Sp* × (Sp | ParaSep).
        if (space >= 0 && (right == Sb.SP || paragraph(right))) return false
        // SB11: SATerm Close* Sp* ParaSep? ÷. SB4 already handles a trailing ParaSep.
        if (space >= 0) return true
        // SB998: otherwise do not break.
        return false
    }

    private fun ignored(c: Int): Boolean = c == Sb.EXTEND || c == Sb.FORMAT
    private fun paragraph(c: Int): Boolean = c == Sb.SEP || c == Sb.CR || c == Sb.LF
    private fun term(c: Int): Boolean = c == Sb.ATERM || c == Sb.STERM
}
