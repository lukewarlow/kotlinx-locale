# Kingfisher Intl implementation

Baseline: `0.4.0-kingfisher.1`. Scope agreed on 2026-11-12.

Kingfisher owns JavaScript coercion, observable option handling, validation and
specification orchestration. This library owns locale data, canonicalization,
exact numeric operations, plural selection, localized patterns and rendering.
Public consumer APIs must not require the internal opt-in.

## Delivery checklist

- [x] Strict locale parsing, lossless variants/extensions, canonical aliases and likely subtags.
- [x] Locale metadata, region preferences, capability reports and supported-value catalogues.
- [x] Exact decimal input, signed zero, special values, rounding and exact public plural operands.
- [x] Structured number/currency/unit/relative-time output and ranges.
- [x] List composition and duration parts/digital metadata.
- [x] Collation options, search tailorings and resolved metadata.
- [x] Display-name types, widths and explicit fallback provenance.
- [x] Selected date/time formats, structured output, style intervals and zone context.
- [x] Public consumer examples, ABI dumps, generator round trips and target verification.

## Public contracts

Keep syntax parsing in core. Put generated canonicalization, likely subtags and
preferences in metadata core/runtime/full modules. Unknown structurally valid
tags do not depend on formatter coverage. Distinguish universal, enumerated and
unknown coverage, and distinguish unsupported operations from missing data.

Extend Decimal to exact coefficients and scales, preserving sign at zero and
visible zeros. Keep checked Long conversions rather than saturation. Provide
public plural operands from already-rounded ASCII digits and compact exponent.
Formatting results contain immutable typed parts; strings are derived from them.
Range parts retain endpoint/shared provenance, and list elements retain indices.

Rounding supports the nine ECMA-402 modes and precision policies. Half-even stays
the Kotlin default; Kingfisher requests half-expand explicitly. A pre-rounded
rendering path performs no second rounding or scaling. Reuse these operations
across currency, units, relative time and duration.

## Boundaries

Prefer additive APIs, replacing lossy contracts when compatibility would add
ambiguity. Gregorian rendering remains the supported calendar scope. Calendar
names and preference lists do not claim non-Gregorian arithmetic support.
Segmentation remains unchanged. Unit selection, duration balancing, JS duration
conversion, instant-to-local conversion and DST calculation stay with callers.

Keep ISO monetary storage separate from CLDR formatting defaults. Add rendering
for arbitrary well-formed currency codes. Extend existing zone-name APIs with
caller-supplied instant/offset/DST context and historical metazones; do not infer
transitions. Implement collation semantics instead of approximating case-first
or search by changing shared tertiary weights or globally dropping accents.

## Validation

Use pinned CLDR/Unicode fixtures and targeted specification cases. Exercise
unknown tags, duplicate variants/extensions, contextual aliases, giant decimals,
tiny fractions, trailing zeros, rounding ties/increments, signed zero, special
values, plural ranges, repeated list elements and part concatenation. Capability
metadata must match full and narrowed bundles. Recheck date/zone/collation
differences individually rather than adding blanket ICU parity waivers.

Update API dumps and consumer documentation with each public change. New modules
need settings, generator feature selection, catalogue and bundle entries. Run JVM
and macOS ARM64 tests and compile the other slim targets, including Android
Native ARM64/X64. Check full configuration, ABI, lint, licences, layering, size
budgets, and generation round trips. A separate consumer must compile without
internal opt-ins. Do not modify Kingfisher or upload a release as part of this
library implementation.

## Implementation record

Implemented against the source checkout, with the new APIs still unreleased.
The public facade examples are in `API.md`; the README includes the metadata,
list and measurement-unit modules and their catalogue entries. Generated data,
feature selection and narrowed bundles use the same pinned CLDR data.

The exact-number model now preserves large coefficients, visible fraction digits
and signed zero. Structured rendering carries semantic parts and range sources;
plural inputs distinguish displayed notation coefficients from restored quantities
used to inflect units and currency names. Locale coverage and lookup results
separate unsupported operations from absent data.

Validation completed:

- All JVM suites, generator round trips, fixture checks and Gradle plugin consumer
  compilation, including narrowed bundles without internal opt-ins.
- macOS ARM64 suites for the affected backends, with final regression runs for
  number, units, currency names, time zones, locale metadata and date/time.
- Compilation for the remaining slim targets: iOS ARM64/simulator ARM64,
  Linux X64/ARM64, MinGW X64 and Android Native ARM64/X64.
- Full-target ABI verification, lint, layering, licence headers and bundle-size
  budgets, with regenerated ABI dumps and size documentation.
- README version-catalogue parsing and compilation of its library and bundle
  accessors, plus whitespace and documentation formatting checks.

Gregorian rendering is the calendar implementation delivered here. Other calendar
names and preferences are metadata only. Segmentation is unchanged. These checks
do not claim complete ECMA-402 or ICU output equivalence: Kingfisher still needs
to integrate the APIs and run its JavaScript conformance tests. No Kingfisher
sources were changed and no artifacts were published.
