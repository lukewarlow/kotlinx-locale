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

package dev.carcara.kotlinx.locale.language

import dev.carcara.kotlinx.locale.Locale

/**
 * [target] written for [locale], following the locale display name algorithm of
 * UTS #35 Part 2.
 *
 * The longest matching language name wins, and the subtags it did not consume
 * are then named and joined with the locale's own patterns:
 *
 * ```
 * Locale.forLanguageTag("sr-Cyrl").displayName(en)  // "Serbian (Cyrillic)"
 * Locale.forLanguageTag("en-GB").displayName(en)    // "British English"
 * Locale.forLanguageTag("en-GB").displayName(en, LanguageDisplay.STANDARD)
 *                                                   // "English (United Kingdom)"
 * ```
 *
 * The extension-subtag steps of the algorithm are not implemented, because
 * [Locale] stops parsing at a singleton subtag and so never carries one. Nor is
 * the `menu` ordering or the nested-bracket replacement.
 *
 * Falls back to the language subtag itself when the source has no name at all,
 * so this never returns nothing.
 */
public fun LanguageNameSource.displayName(
    target: Locale,
    locale: Locale = Locale.current,
    display: LanguageDisplay = LanguageDisplay.DIALECT,
    style: LanguageNameStyle = LanguageNameStyle.STANDARD,
): String {
    val patterns = displayPatternsOrNull(locale) ?: LocaleDisplayPatterns.Root

    // Step 1: longest match on the language table. Under STANDARD only the bare
    // subtag is tried, which is what turns British English into English (United
    // Kingdom).
    var base: String? = null
    var consumedScript = false
    var consumedRegion = false
    if (display == LanguageDisplay.DIALECT) {
        val script = target.script
        val region = target.region
        if (script != null && region != null) {
            base = languageNameOrNull("${target.language}_${script}_$region", style, locale)
            if (base != null) {
                consumedScript = true
                consumedRegion = true
            }
        }
        if (base == null && region != null) {
            base = languageNameOrNull("${target.language}_$region", style, locale)
            if (base != null) consumedRegion = true
        }
        if (base == null && script != null) {
            base = languageNameOrNull("${target.language}_$script", style, locale)
            if (base != null) consumedScript = true
        }
    }
    if (base == null) base = languageNameOrNull(target.language, style, locale)
    val languageName = base ?: target.language

    // Step 2: whatever the winner did not consume becomes a qualifier, and an
    // unmatched subtag contributes its own code rather than disappearing.
    val qualifiers = ArrayList<String>(3)
    target.script?.takeUnless { consumedScript }?.let { qualifiers += scriptNameOrNull(it, locale) ?: it }
    target.region?.takeUnless { consumedRegion }?.let { qualifiers += regionNameOrNull(it, locale) ?: it }
    target.variant?.let { qualifiers += it }

    // Step 3: none, one, or several joined by the separator first.
    if (qualifiers.isEmpty()) return languageName
    val joined = qualifiers.reduce { left, right ->
        patterns.localeSeparator.replace("{0}", left).replace("{1}", right)
    }
    return patterns.localePattern.replace("{0}", languageName).replace("{1}", joined)
}

/**
 * [target]'s name in its own language: `日本語`, `čeština`, `português`.
 *
 * The same call as [displayName] with the target and the display locale equal,
 * which is where CLDR keeps a native name: `cs.xml` is the file that says
 * `čeština`.
 *
 * Note that CLDR stores these as the language writes them, which in many
 * languages is lower case. A picker row that wants `Čeština` is asking for a
 * capitalization transform, which is a separate question from the name.
 */
public fun LanguageNameSource.nativeDisplayName(
    target: Locale,
    display: LanguageDisplay = LanguageDisplay.DIALECT,
    style: LanguageNameStyle = LanguageNameStyle.STANDARD,
): String = displayName(target, target, display, style)

/** The name of the language [subtag] in [locale]; falls back to the subtag. */
public fun LanguageNameSource.languageName(
    subtag: String,
    locale: Locale = Locale.current,
    style: LanguageNameStyle = LanguageNameStyle.STANDARD,
): String = languageNameOrNull(subtag, style, locale) ?: subtag

/** The name of the script [code] in [locale]; falls back to the code. */
public fun LanguageNameSource.scriptName(code: String, locale: Locale = Locale.current): String = scriptNameOrNull(code, locale) ?: code

/** The name of the region [code] in [locale]; falls back to the code. */
public fun LanguageNameSource.regionName(code: String, locale: Locale = Locale.current): String = regionNameOrNull(code, locale) ?: code

/** Compose a locale name with explicit missing-name and code-substitution semantics. */
public fun LanguageNameSource.localeName(
    target: Locale,
    locale: Locale = Locale.current,
    display: LanguageDisplay = LanguageDisplay.DIALECT,
    width: dev.carcara.kotlinx.locale.NameWidth = dev.carcara.kotlinx.locale.NameWidth.LONG,
    fallback: dev.carcara.kotlinx.locale.NameFallback = dev.carcara.kotlinx.locale.NameFallback.NONE,
): dev.carcara.kotlinx.locale.LocaleLookup<dev.carcara.kotlinx.locale.DisplayName> {
    val values = ArrayList<dev.carcara.kotlinx.locale.DisplayName>()
    var failure: dev.carcara.kotlinx.locale.LocaleLookup<Nothing>? = null
    fun name(type: dev.carcara.kotlinx.locale.DisplayNameType, code: String, allowCode: Boolean = true): String? = when (
        val result = lookupName(
            type,
            code,
            locale,
            width,
            if (allowCode) fallback else dev.carcara.kotlinx.locale.NameFallback.NONE,
        )
    ) {
        is dev.carcara.kotlinx.locale.LocaleLookup.Value -> result.value.also(values::add).text
        is dev.carcara.kotlinx.locale.LocaleLookup.Unsupported -> {
            failure = result
            null
        }
        dev.carcara.kotlinx.locale.LocaleLookup.MissingData -> null
    }
    val patterns = displayPatternsOrNull(locale) ?: LocaleDisplayPatterns.Root
    var base: String? = null
    var scriptUsed = false
    var regionUsed = false
    if (display == LanguageDisplay.DIALECT) {
        if (target.script != null && target.region != null) {
            base = name(dev.carcara.kotlinx.locale.DisplayNameType.LANGUAGE, "${target.language}-${target.script}-${target.region}", false)
            if (base != null) {
                scriptUsed = true
                regionUsed = true
            }
        }
        if (base == null && target.region != null) {
            base = name(dev.carcara.kotlinx.locale.DisplayNameType.LANGUAGE, "${target.language}-${target.region}", false)
            if (base != null) regionUsed = true
        }
        if (base == null && target.script != null) {
            base = name(dev.carcara.kotlinx.locale.DisplayNameType.LANGUAGE, "${target.language}-${target.script}", false)
            if (base != null) scriptUsed = true
        }
    }
    base = base ?: name(dev.carcara.kotlinx.locale.DisplayNameType.LANGUAGE, target.language)
        ?: return failure ?: dev.carcara.kotlinx.locale.LocaleLookup.MissingData
    val qualifiers = ArrayList<String>()
    target.script?.takeUnless { scriptUsed }?.let {
        qualifiers.add(
            name(dev.carcara.kotlinx.locale.DisplayNameType.SCRIPT, it)
                ?: return failure ?: dev.carcara.kotlinx.locale.LocaleLookup.MissingData,
        )
    }
    target.region?.takeUnless { regionUsed }?.let {
        qualifiers.add(
            name(dev.carcara.kotlinx.locale.DisplayNameType.REGION, it)
                ?: return failure ?: dev.carcara.kotlinx.locale.LocaleLookup.MissingData,
        )
    }
    for (variant in target.variants) {
        qualifiers.add(
            name(dev.carcara.kotlinx.locale.DisplayNameType.VARIANT, variant)
                ?: return failure ?: dev.carcara.kotlinx.locale.LocaleLookup.MissingData,
        )
    }
    fun escapeQualifier(value: String): String = value.replace('(', '[').replace(')', ']').replace('（', '［').replace('）', '］')
    val qualifier = qualifiers.map(::escapeQualifier).reduceOrNull { left, right ->
        patterns.localeSeparator.replace("{0}", left).replace("{1}", right)
    }
    val text = if (qualifier == null) base else patterns.localePattern.replace("{0}", base).replace("{1}", qualifier)
    val origin = when {
        values.any { it.origin == dev.carcara.kotlinx.locale.NameOrigin.CODE } -> dev.carcara.kotlinx.locale.NameOrigin.CODE
        values.any {
            it.origin == dev.carcara.kotlinx.locale.NameOrigin.WIDTH_FALLBACK
        } -> dev.carcara.kotlinx.locale.NameOrigin.WIDTH_FALLBACK
        else -> dev.carcara.kotlinx.locale.NameOrigin.LOCALIZED
    }
    return dev.carcara.kotlinx.locale.LocaleLookup.Value(dev.carcara.kotlinx.locale.DisplayName(text, origin, width))
}
