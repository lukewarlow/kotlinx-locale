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

package dev.carcara.kotlinx.locale.timezone

/** The caller owns instant conversion and daylight-saving calculations. */
public data class TimeZoneNameContext(
    public val identifier: String,
    public val offsetSeconds: Int?,
    public val epochSeconds: Long? = null,
    public val isDaylight: Boolean? = null,
    public val observesDaylight: Boolean? = null,
    public val numberingSystem: String? = null,
) {
    init {
        require(offsetSeconds == null || offsetSeconds in -86399..86399)
    }
    public companion object
}
