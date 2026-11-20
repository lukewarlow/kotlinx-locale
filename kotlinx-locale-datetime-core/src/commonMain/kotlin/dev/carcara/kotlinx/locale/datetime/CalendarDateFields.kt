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

package dev.carcara.kotlinx.locale.datetime

/**
 * Already-converted fields. No calendar arithmetic or balancing is performed.
 *
 * [monthCode] uses M01..M13, with L for a leap month. [monthNumber] is the
 * numeric month to print, supplied separately because its relationship to a
 * month code depends on the calendar and year. [era] is a stable code returned
 * by the source's era catalogue. [year] is the extended year, [eraYear] the
 * year within that era. [weekday] is ISO 1 (Monday) through 7 (Sunday).
 *
 * Hebrew M05L denotes Adar I; M06 in a leap year uses Adar II names.
 * Chinese/Dangi leap codes use the ordinary month's name and leap pattern.
 */
public data class CalendarDateFields(
    public val calendar: String,
    public val year: Int? = null,
    public val eraYear: Int? = year,
    public val era: String? = null,
    public val monthCode: String? = null,
    public val monthNumber: Int? = null,
    public val day: Int? = null,
    public val weekday: Int? = null,
    public val isLeapYear: Boolean = false,
    public val relatedYear: Int? = null,
    public val cyclicYear: Int? = null,
) {
    init {
        val code = monthCode
        require(
            code == null ||
                (
                    code.length in 3..4 &&
                        code[0] == 'M' &&
                        ((code[1] == '0' && code[2] in '1'..'9') || (code[1] == '1' && code[2] in '0'..'3')) &&
                        (code.length == 3 || code[3] == 'L')
                    ),
        ) { "Invalid month code" }
        require(monthNumber == null || monthNumber in 1..13)
        require(day == null || day in 1..31)
        require(weekday == null || weekday in 1..7)
        require(cyclicYear == null || cyclicYear in 1..60)
    }
    public companion object
}
