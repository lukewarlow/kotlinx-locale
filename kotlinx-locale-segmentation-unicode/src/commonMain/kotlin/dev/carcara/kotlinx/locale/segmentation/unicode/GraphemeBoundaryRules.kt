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

/** Default Unicode 18 extended grapheme rules, independently of legacy person-name rules. */
internal class GraphemeBoundaryRules(encoded: String) {
    private val table = BoundaryPropertyTable(encoded)

    fun segment(text: String): SegmentationBoundaries {
        val input = BoundaryInput(text, table, 4)
        val c = input.classes
        var regionalCount = 0
        var linkerBefore = false
        var pictographBefore = false
        var pictographBeforeZwj = false
        return input.collect(wordSegments = false) { i ->
            // Update the left context exactly once for each code point boundary.
            val a = c[i - 1]
            val b = c[i]
            regionalCount = if (a == REGIONAL_INDICATOR) regionalCount + 1 else 0
            linkerBefore = when (input.flags[i - 1] % 4) {
                INCB_LINKER -> true
                INCB_EXTEND -> linkerBefore
                else -> false
            }
            pictographBeforeZwj = a == ZWJ && pictographBefore
            pictographBefore = when (a) {
                EXTENDED_PICTOGRAPHIC -> true
                EXTEND -> pictographBefore
                else -> false
            }
            when {
                // GB3, GB4, GB5: controls take precedence over all joining rules.
                a == CR && b == LF -> false
                a in CR..CONTROL || b in CR..CONTROL -> true
                // GB6, GB7, GB8: conjoining Hangul jamo and syllables.
                a == HANGUL_L && (b == HANGUL_L || b == HANGUL_V || b == HANGUL_LV || b == HANGUL_LVT) -> false
                (a == HANGUL_LV || a == HANGUL_V) && (b == HANGUL_V || b == HANGUL_T) -> false
                (a == HANGUL_LVT || a == HANGUL_T) && b == HANGUL_T -> false
                // GB9, GB9a, GB9b.
                b == EXTEND || b == ZWJ || b == SPACING_MARK || a == PREPEND -> false
                // GB9c (Unicode 18): Linker Extend* × Consonant, with no preceding-consonant requirement.
                linkerBefore && input.flags[i] % 4 == INCB_CONSONANT -> false
                // GB11: Extended_Pictographic Extend* ZWJ × Extended_Pictographic.
                a == ZWJ && b == EXTENDED_PICTOGRAPHIC && pictographBeforeZwj -> false
                // GB12, GB13: pair regional indicators from the beginning of the run.
                a == REGIONAL_INDICATOR && b == REGIONAL_INDICATOR && regionalCount % 2 == 1 -> false
                else -> true // GB999
            }
        }
    }

    private companion object {
        const val CR = 1
        const val LF = 2
        const val CONTROL = 3
        const val EXTEND = 4
        const val ZWJ = 5
        const val REGIONAL_INDICATOR = 6
        const val PREPEND = 7
        const val SPACING_MARK = 8
        const val HANGUL_L = 9
        const val HANGUL_V = 10
        const val HANGUL_T = 11
        const val HANGUL_LV = 12
        const val HANGUL_LVT = 13
        const val EXTENDED_PICTOGRAPHIC = 14
        const val INCB_CONSONANT = 1
        const val INCB_LINKER = 2
        const val INCB_EXTEND = 3
    }
}
