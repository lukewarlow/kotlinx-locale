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

package dev.carcara.kotlinx.locale.currency.cldr.runtime

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Bounded immutable publication, including negative lookups; never mutate a shared map. */
@OptIn(ExperimentalAtomicApi::class)
internal class PreparedCurrencyCache<K, V>(private val limit: Int = 64) {
    private val entries = AtomicReference<Map<K, V>>(emptyMap())
    fun getOrPut(key: K, create: () -> V): V {
        val initial = entries.load()
        if (initial.containsKey(key)) return initial.getValue(key)
        val value = create()
        while (true) {
            val previous = entries.load()
            if (previous.containsKey(key)) return previous.getValue(key)
            val retained = if (previous.size < limit) previous else previous.entries.drop(1).associate { it.key to it.value }
            if (entries.compareAndSet(previous, retained + (key to value))) return value
        }
    }
}
