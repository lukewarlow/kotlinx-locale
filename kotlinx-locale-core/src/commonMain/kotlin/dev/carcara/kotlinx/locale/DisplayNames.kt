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

package dev.carcara.kotlinx.locale

public enum class NameWidth {
    LONG,
    SHORT,
    NARROW,
    ;

    public companion object
}
public enum class NameFallback {
    NONE,
    CODE,
    ;

    public companion object
}
public enum class NameOrigin {
    LOCALIZED,
    WIDTH_FALLBACK,
    CODE,
    ;

    public companion object
}
public enum class DisplayNameType {
    LANGUAGE,
    SCRIPT,
    REGION,
    CURRENCY,
    CALENDAR,
    DATE_TIME_FIELD,
    VARIANT,
    ;

    public companion object
}

/** A name and whether width fallback or code substitution contributed to it. */
public data class DisplayName(public val text: String, public val origin: NameOrigin, public val width: NameWidth?) {
    public companion object
}
